package gr.katsip.synefo.storm.operators.synefo_comp_ops;

import gr.katsip.synefo.metric.TaskStatistics;

import java.io.IOException;
import java.io.Serializable;

import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Select implements Serializable, AbstractCrypefoOperator  {

	/**
	 * 
	 */
	private static final long serialVersionUID = -7785533234885136231L;

	Logger logger = LoggerFactory.getLogger(Select.class);

	ArrayList<Integer> selections;

	String predicate;

	int clientOwnerId;

	int attribute;

	int type;	//equi=0; "<" = 1; "<=" = 2...; 

	private List<Values> stateValues;

	private Fields stateSchema;

	private Fields output_schema;

	private HashMap<String, Integer> encryptionData = new HashMap<String,Integer>();

	private int statReportPeriod;

	private DataCollector dataSender = null;

	private String zooIP;

	private Integer zooPort;

	private String ID;

	private ZooKeeper zk = null;

	private  Watcher SPSRetrieverWatcher =null;
	/**
	 * 
	 * @param returnSet
	 * @param pred
	 * @param att
	 * @param typ
	 * @param client
	 * @param statReportPeriod the period of reporting statistics (in tuples)
	 * @param ID
	 * @param zooIP
	 * @param zooPort
	 */
	public Select(ArrayList<Integer> returnSet, String pred, int att, int typ, int client, 
			int statReportPeriod, String ID, String zooIP, Integer zooPort) {
		predicate = pred;
		attribute = att;
		type = typ;
		clientOwnerId = client;
		selections = new ArrayList<Integer>(returnSet);
		encryptionData.put("pln",0);
		encryptionData.put("RND",0);
		encryptionData.put("DET",0);
		encryptionData.put("OPE",0);
		encryptionData.put("HOM",0);
		this.statReportPeriod = statReportPeriod;
		this.ID = ID;
		this.zooIP = zooIP;
		this.zooPort = zooPort;
		dataSender = null;
		this.zooIP = zooIP;
		this.zooPort = zooPort;
	}

	@Override
	public void init(List<Values> stateValues) {
		this.stateValues = stateValues;
	}

	@Override
	public List<Values> getStateValues() {
		return stateValues;
	}

	public Fields getStateSchema() {
		return stateSchema;
	}

	@Override
	public Fields getOutputSchema() {
		return output_schema;
	}

	@Override
	public void mergeState(Fields receivedStateSchema, List<Values> receivedStateValues) {
		//Nothing to do since no state is kept
	}

	@Override
	public void setOutputSchema(Fields _output_schema) {
		output_schema = new Fields(_output_schema.toList());
	}

	@Override
	public void setStateSchema(Fields stateSchema) {
		this.stateSchema = new Fields(stateSchema.toList());
	}

	@Override
	public List<Values> execute(TaskStatistics statistics, Fields fields,
			Values values) {
		if(dataSender == null) {
			dataSender = new DataCollector(zooIP, zooPort, statReportPeriod, ID);
		}
		SPSRetrieverWatcher = new Watcher() {
			@Override
			public void process(WatchedEvent event) {
				String path = event.getPath();
				System.out.println("Select Received event type: " + event.getType()+ " path "+event.getPath());
				if(event.getType() == Event.EventType.NodeDataChanged) {
					//Retrieve operator
					getDataAndWatch();
					System.out.println("NodeDataChanged event: " + path);
					try {
						byte[] data =zk.getData(path,false,null);
						handleUpdate(new String(data));
					} catch (KeeperException | InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}else if(event.getType() == null) {
					//Retrieve new children
					getDataAndWatch();
				}else{
					getDataAndWatch();
				}
			}
		};
		if(zk==null){
			try {
				zk = new ZooKeeper(this.zooIP + ":" + this.zooPort, 100000, SPSRetrieverWatcher);
			} catch (IOException e) {
				e.printStackTrace();}
		}
		if(!values.get(0).toString().contains("SPS")) {
			System.out.println("Predicate: "+predicate+ " SELECTION: "+values.get(0).toString());
			String[] tuples = values.get(0).toString().split(Pattern.quote("//$$$//"));
			boolean matches  = false;
			if(type == 0) {
				matches = equiSelect(tuples);
			}else if(type == 1 || type ==2 ) {
				matches = lessSelect(tuples);
			}else if(type == 3 || type == 4) {
				matches = greaterSelect(tuples);
			}else {
				matches = false;
			}
			String[] encUse= tuples[tuples.length-1].split(" ");
			for(int k =0;k<encUse.length;k++){
				encryptionData.put(encUse[k], encryptionData.get(encUse[k])+1);
			}
			updateData(statistics);
			Values val = new Values(); val.addAll(values);
			ArrayList<Values> valz = new ArrayList<Values>();
			valz.add(val);
			if(matches)
				return valz;
			else
				return new ArrayList<Values>();
		}else {
			return new ArrayList<Values>();
		}
	}

	public List<Values> execute(Fields fields, Values values) {
		if(dataSender == null) {
			dataSender = new DataCollector(zooIP, zooPort, statReportPeriod, ID);
		}
		if(!values.get(0).toString().contains("SPS")) {
			String[] tuples = values.get(0).toString().split("//$$$//");
			boolean matches  = false;
			if(type == 0) {
				matches = equiSelect(tuples);
			}else if(type == 1 || type ==2 ) {
				matches = lessSelect(tuples);
			}else if(type == 3 || type == 4) {
				matches = greaterSelect(tuples);
			}else {
				matches = false;
			}
			encryptionData.put(tuples[tuples.length-1], encryptionData.get(tuples[tuples.length-1])+1);
			updateData(null);
			Values val = new Values(); val.addAll(values);
			ArrayList<Values> valz = new ArrayList<Values>();
			valz.add(val);
			if(matches)
				return valz;
			else
				return new ArrayList<Values>();
		}else {
			return new ArrayList<Values>();
		}
	}

	public boolean equiSelect(String[] tuple) {
		if(predicate.equals("*") && predicate == null)
			return true;
		if(tuple[attribute].equalsIgnoreCase(predicate))
			return true;
		else
			return false;
	}

	public boolean lessSelect(String[] tuple) {
		if(type == 1) {
			if(Integer.parseInt(tuple[attribute])<Integer.parseInt(predicate))
				return true;
			else
				return false;
		}else {
			if(Integer.parseInt(tuple[attribute])<=Integer.parseInt(predicate)) 
				return true;
			else
				return false;
		}
	}

	public boolean greaterSelect(String[] tuple) {
		if(type == 3){
			if(Integer.parseInt(tuple[attribute])>Integer.parseInt(predicate))
				return true;
			else
				return false;	
		}else {
			if(Integer.parseInt(tuple[attribute]) >= Integer.parseInt(predicate))
				return true;
			else
				return false;
		}
	}

	public void updateData(TaskStatistics stats) {
		int CPU = 0;
		int memory = 0;
		int latency = 0;
		int throughput = 0;
		int sel = 0;
		if(stats != null) {
			String tuple = 	ID + "," + stats.getCpuLoad() + "," + stats.getMemory() + "," + stats.getWindowLatency() + "," + 
					stats.getWindowThroughput() + "," + stats.getSelectivity() + "," + 
					encryptionData.get("pln") + "," + 
					encryptionData.get("RND") + "," + 
					encryptionData.get("DET") + "," + 
					encryptionData.get("OPE") + ","  + 
					encryptionData.get("HOM");

			dataSender.addToBuffer(tuple);
			encryptionData.put("pln",0);
			encryptionData.put("RND",0);
			encryptionData.put("DET",0);
			encryptionData.put("OPE",0);
			encryptionData.put("HOM",0);
		}else {
			String tuple = 	ID + "," + CPU + "," + memory + "," + latency + "," + 
					throughput + "," + sel + "," + 
					encryptionData.get("pln") + "," + 
					encryptionData.get("RND") + "," + 
					encryptionData.get("DET") + "," + 
					encryptionData.get("OPE") + ","  + 
					encryptionData.get("HOM");

			dataSender.addToBuffer(tuple);
			encryptionData.put("pln",0);
			encryptionData.put("RND",0);
			encryptionData.put("DET",0);
			encryptionData.put("OPE",0);
			encryptionData.put("HOM",0);
		}
	}

	public void getDataAndWatch() {
		System.out.println("Select watch reset");
		zk.getData("/SPS", 
				true, 
				getSPSCallback, 
				"/SPS/".getBytes());
	}

	private void handleUpdate(String data){
		String[] sp = data.split(",");
		if(sp[0].equalsIgnoreCase("select")){
			predicate = sp[1];
			System.out.println("Predicate changed to: "+predicate);
		}
	}

	private transient DataCallback getSPSCallback = new DataCallback() {
		@Override
		public void processResult(int rc, String path, Object ctx, byte[] data,
				Stat stat) {
			switch(Code.get(rc)) {
			case CONNECTIONLOSS:
				System.out.println("getSPSCallback(): CONNECTIONLOSS");
				getDataAndWatch();
				break;
			case NONODE:
				System.out.println("getSPSCallback(): NONODE");
				break;
			case OK:
				System.out.println("getSPSCallback(): Successfully retrieved new predicate: " + 
						new String(data));
				handleUpdate(new String(data));
				getDataAndWatch();
				break;
			default:
				System.out.println("getDataCallback(): Unexpected scenario: " + 
						KeeperException.create(Code.get(rc), path) );
				break;
			}
		}

	};
	@Override
	public void updateOperatorName(String operatorName) {
		this.ID = operatorName;
	}

}
