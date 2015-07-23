package gr.katsip.synefo.storm.api;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gr.katsip.synefo.metric.TaskStatistics;
import gr.katsip.synefo.storm.lib.SynefoMessage;
import gr.katsip.synefo.storm.lib.SynefoMessage.Type;
import gr.katsip.synefo.storm.operators.AbstractOperator;
import gr.katsip.synefo.storm.operators.AbstractStatOperator;
import gr.katsip.synefo.utils.SynefoConstant;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

/**
 * 
 * @author Nick R. Katsipoulakis
 *
 */
public class SynefoBolt extends BaseRichBolt {

	/**
	 * 
	 */
	private static final long serialVersionUID = 4011052074675303959L;

	Logger logger = LoggerFactory.getLogger(SynefoBolt.class);

	private static final String stormHome = "/opt/apache-storm-0.9.4/logs/";

	private AsynchronousFileChannel statisticFileChannel = null;

	private CompletionHandler<Integer, Object> statisticFileHandler = null;

	private AsynchronousFileChannel scaleEventFileChannel = null;

	private CompletionHandler<Integer, Object> scaleEventFileHandler = null;

	private Long statisticFileOffset = 0L;

	private Long scaleEventFileOffset = 0L;

	private static final int statReportPeriod = 5000;

	private String taskName;

	private Integer workerPort;

	private int downStreamIndex;

	private OutputCollector collector;

	private ArrayList<String> downstreamTasks;

	private ArrayList<Integer> intDownstreamTasks;

	private ArrayList<String> activeDownstreamTasks;

	private ArrayList<Integer> intActiveDownstreamTasks;

	private String synefoServerIP = null;

	private int taskID = -1;

	private String taskIP;

	private Integer synefoServerPort = -1;

	private TaskStatistics statistics;

	private AbstractOperator operator;

	private List<Values> stateValues;

	private ZooPet zooPet;

	private String zooIP;

	private int reportCounter;

	private boolean autoScale;

	private boolean warmFlag;

	private boolean statOperatorFlag;

	private enum OpLatencyState {
		na,
		s_1,
		s_2,
		s_3,
		r_1,
		r_2,
		r_3
	}
	
	private HashMap<String, String> sequenceInitiator;
	
	private HashMap<String, ArrayList<Long>> receivedTimestampIndex;
	
	private HashMap<String, ArrayList<Long>> localTimestampIndex;
	
	private HashMap<String, OpLatencyState> sequenceStateIndex;
	
	public SynefoBolt(String task_name, String synEFO_ip, Integer synEFO_port, 
			AbstractOperator operator, String zooIP, boolean autoScale) {
		taskName = task_name;
		workerPort = -1;
		synefoServerIP = synEFO_ip;
		synefoServerPort = synEFO_port;
		downstreamTasks = null;
		intDownstreamTasks = null;
		activeDownstreamTasks = null;
		intActiveDownstreamTasks = null;
		this.operator = operator;
		stateValues = new ArrayList<Values>();
		this.operator.init(stateValues);
		this.zooIP = zooIP;
		reportCounter = 0;
		this.autoScale = autoScale;
		warmFlag = false;
		if(operator instanceof AbstractStatOperator)
			statOperatorFlag = true;
		else
			statOperatorFlag = false;
	}

	/**
	 * The function for registering to Synefo server
	 */
	@SuppressWarnings("unchecked")
	public void registerToSynEFO() {
		Socket socket;
		ObjectOutputStream output;
		ObjectInputStream input;
		socket = null;
		SynefoMessage msg = new SynefoMessage();
		msg._type = Type.REG;
		try {
			taskIP = InetAddress.getLocalHost().getHostAddress();
			msg._values.put("TASK_IP", taskIP);
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		msg._values.put("TASK_TYPE", "BOLT");
		msg._values.put("TASK_NAME", taskName);
		msg._values.put("TASK_ID", Integer.toString(taskID));
		msg._values.put("WORKER_PORT", Integer.toString(workerPort));
		try {
			socket = new Socket(synefoServerIP, synefoServerPort);
			output = new ObjectOutputStream(socket.getOutputStream());
			input = new ObjectInputStream(socket.getInputStream());
			output.writeObject(msg);
			output.flush();
			msg = null;
			ArrayList<String> downstream = null;
			downstream = (ArrayList<String>) input.readObject();
			if(downstream != null && downstream.size() > 0) {
				downstreamTasks = new ArrayList<String>(downstream);
				intDownstreamTasks = new ArrayList<Integer>();
				Iterator<String> itr = downstreamTasks.iterator();
				while(itr.hasNext()) {
					String[] tokens = itr.next().split("[:@]");
					Integer task = Integer.parseInt(tokens[1]);
					intDownstreamTasks.add(task);
				}
			}else {
				downstreamTasks = new ArrayList<String>();
				intDownstreamTasks = new ArrayList<Integer>();
			}
			ArrayList<String> activeDownstream = null;
			activeDownstream = (ArrayList<String>) input.readObject();
			if(activeDownstream != null && activeDownstream.size() > 0) {
				activeDownstreamTasks = new ArrayList<String>(activeDownstream);
				intActiveDownstreamTasks = new ArrayList<Integer>();
				Iterator<String> itr = activeDownstreamTasks.iterator();
				while(itr.hasNext()) {
					String[] tokens = itr.next().split("[:@]");
					Integer task = Integer.parseInt(tokens[1]);
					intActiveDownstreamTasks.add(task);
				}
				downStreamIndex = 0;
			}else {
				activeDownstreamTasks = new ArrayList<String>();
				intActiveDownstreamTasks = new ArrayList<Integer>();
				downStreamIndex = 0;
			}
			/**
			 * Closing channels of communication with 
			 * SynEFO server
			 */
			output.flush();
			output.close();
			input.close();
			socket.close();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		/**
		 * Handshake with ZooKeeper
		 */
		zooPet.start();
		zooPet.getScaleCommand();
		StringBuilder strBuild = new StringBuilder();
		strBuild.append("+EFO-BOLT (" + taskName + ":" + taskID + ") list of active tasks: ");
		for(String activeTask : activeDownstreamTasks) {
			strBuild.append(activeTask + " ");
		}
		logger.info(strBuild.toString());
		logger.info("+EFO-BOLT (" + 
				taskName + ":" + taskID + 
				") registered to synEFO successfully, timestamp: " + System.currentTimeMillis() + ".");
		/**
		 * Updating operator name for saving the statistics data in the 
		 * database server accordingly
		 */
		if(statOperatorFlag == true)
			((AbstractStatOperator) operator).updateOperatorName(
					taskName + ":" + taskID + "@" + taskIP);
		sequenceInitiator = new HashMap<String, String>();
		receivedTimestampIndex = new HashMap<String, ArrayList<Long>>();
		localTimestampIndex = new HashMap<String, ArrayList<Long>>();
		sequenceStateIndex = new HashMap<String, OpLatencyState>();
	}

	public void prepare(@SuppressWarnings("rawtypes") Map conf, TopologyContext context, OutputCollector collector) {
		this.collector = collector;
		taskID = context.getThisTaskId();
		workerPort = context.getThisWorkerPort();
		/**
		 * Update the taskName and extend it with the task-id (support for multi-core)
		 */
		taskName = taskName + "_" + taskID;
		try {
			taskIP = InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		zooPet = new ZooPet(zooIP, taskName, taskID, taskIP);
		if(downstreamTasks == null && activeDownstreamTasks == null)
			registerToSynEFO();
		if(this.statisticFileChannel == null) {
			try {
				File f = new File(stormHome + 
						taskName + ":" + taskID + "@" + taskIP + "-stats.log");
				if(f.exists() == false)
					statisticFileChannel = AsynchronousFileChannel.open(Paths.get(stormHome + 
							taskName + ":" + taskID + "@" + taskIP + "-stats.log"), 
							StandardOpenOption.WRITE, StandardOpenOption.CREATE);
				else {
					statisticFileChannel = AsynchronousFileChannel.open(Paths.get(stormHome + 
							taskName + ":" + taskID + "@" + taskIP + "-stats.log"), 
							StandardOpenOption.WRITE);
					this.statisticFileOffset = statisticFileChannel.size();
					byte[] buffer = (System.currentTimeMillis() + "," + "STATS-EXIST\n").toString().getBytes();
					if(this.statisticFileChannel != null && this.statisticFileHandler != null) {
						statisticFileChannel.write(
								ByteBuffer.wrap(buffer), this.statisticFileOffset, "stat write", statisticFileHandler);
						statisticFileOffset += buffer.length;
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			statisticFileHandler = new CompletionHandler<Integer, Object>() {
				@Override
				public void completed(Integer result, Object attachment) {}
				@Override
				public void failed(Throwable exc, Object attachment) {}
			};
			statisticFileOffset = 0L;
		}
		if(this.scaleEventFileChannel == null) {
			try {
				File f = new File(stormHome + 
						taskName + ":" + taskID + "@" + taskIP + "-scale-events.log");
				if(f.exists() == false)
					scaleEventFileChannel = AsynchronousFileChannel.open(Paths.get(stormHome + 
							taskName + ":" + taskID + "@" + taskIP + "-scale-events.log"), 
							StandardOpenOption.WRITE, StandardOpenOption.CREATE);
				else {
					scaleEventFileChannel = AsynchronousFileChannel.open(Paths.get(stormHome + 
							taskName + ":" + taskID + "@" + taskIP + "-scale-events.log"), 
							StandardOpenOption.WRITE);
					this.scaleEventFileOffset = scaleEventFileChannel.size();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			scaleEventFileHandler = new CompletionHandler<Integer, Object>() {
				@Override
				public void completed(Integer result, Object attachment) {}
				@Override
				public void failed(Throwable exc, Object attachment) {}
			};
			scaleEventFileOffset = 0L;
		}
		statistics = new TaskStatistics(statReportPeriod);
	}
	
	public void manageLatencySequence(String synefoHeader, long currentTimestamp) {
		ArrayList<Long> receivedTimestamps = null;
		ArrayList<Long> localTimestamps = null;
		String[] headerTokens = synefoHeader.split(":");
		/**
		 * [0] -> initial-sender-task-id
		 * [1] -> initial-sender-sequence-number
		 * [2] -> upstream-sender-task-id
		 */
		String[] sequenceTokens = headerTokens[0].split("-")[1].split("#");
		String sequenceName = sequenceTokens[0] + "#" + sequenceTokens[1];
		OpLatencyState receivedLatencyState = OpLatencyState.valueOf(headerTokens[1]);
		Long receivedTimestamp = Long.parseLong(headerTokens[2]);
		String logLine = currentTimestamp + " (1) sequence-num: " + sequenceName + ", op-state: " + receivedLatencyState + "\n";
		byte[] buffer = logLine.getBytes();
		if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
			scaleEventFileChannel.write(
					ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
			scaleEventFileOffset += buffer.length;
		}
		if(sequenceInitiator.containsKey(sequenceName)) {
			/**
			 * Check if the sequence upstream task id is the current 
			 * one in the index
			 */
			if(sequenceInitiator.get(sequenceName).equals(sequenceTokens[2])) {
				/**
				 * 3 different cases of sequence
				 */
				if(receivedLatencyState == OpLatencyState.s_2 && 
						sequenceStateIndex.get(sequenceName) == OpLatencyState.r_1) {
					sequenceStateIndex.put(sequenceName, OpLatencyState.r_2);
					receivedTimestamps = receivedTimestampIndex.get(sequenceName);
					receivedTimestamps.add(receivedTimestamp);
					receivedTimestampIndex.put(sequenceName, receivedTimestamps);
					localTimestamps = localTimestampIndex.get(sequenceName);
					localTimestamps.add(currentTimestamp);
					localTimestampIndex.put(sequenceName, localTimestamps);
					/**
					 * Check for errors
					 */
					if(Math.abs(receivedTimestamps.get(1) - receivedTimestamps.get(0)) < 1000) {
						logLine = currentTimestamp + ",A," + receivedTimestamps.get(1) + "," + 
								+ receivedTimestamps.get(0) + "," + sequenceName + "\n";
						buffer = logLine.getBytes();
						if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
							scaleEventFileChannel.write(
									ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
							scaleEventFileOffset += buffer.length;
						}
					}
					if(Math.abs(localTimestamps.get(1) - localTimestamps.get(0)) < 1000) {
						logLine = currentTimestamp + ",B," + localTimestamps.get(1) + "," + 
								+ localTimestamps.get(0) + "," + sequenceName + "\n";
						buffer = logLine.getBytes();
						if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
							scaleEventFileChannel.write(
									ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
							scaleEventFileOffset += buffer.length;
						}
					}
					Values latencyMetricTuple = new Values();
					latencyMetricTuple.add(SynefoConstant.OP_LATENCY_METRIC + "-" + sequenceName + 
							"#" + taskID + ":" + 
							OpLatencyState.s_2.toString() + ":" + 
							currentTimestamp);
					for(int i = 0; i < operator.getOutputSchema().size(); i++)
						latencyMetricTuple.add(null);
					for(Integer d_task : intActiveDownstreamTasks)
						collector.emitDirect(d_task, latencyMetricTuple);
				}else if(receivedLatencyState == OpLatencyState.s_3 && 
						sequenceStateIndex.get(sequenceName) == OpLatencyState.r_2) {
					sequenceStateIndex.put(sequenceName, OpLatencyState.r_3);
					receivedTimestamps = receivedTimestampIndex.get(sequenceName);
					receivedTimestamps.add(receivedTimestamp);
					receivedTimestampIndex.put(sequenceName, receivedTimestamps);
					localTimestamps = localTimestampIndex.get(sequenceName);
					localTimestamps.add(currentTimestamp);
					localTimestampIndex.put(sequenceName, localTimestamps);
					/**
					 * Check for errors
					 */
					if(Math.abs(receivedTimestamps.get(2) - receivedTimestamps.get(1)) < 1000) {
						logLine = currentTimestamp + ",C," + receivedTimestamps.get(2) + "," + 
								+ receivedTimestamps.get(1) + "," + sequenceName + "\n";
						buffer = logLine.getBytes();
						if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
							scaleEventFileChannel.write(
									ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
							scaleEventFileOffset += buffer.length;
						}
					}
					if(Math.abs(localTimestamps.get(2) - localTimestamps.get(1)) < 1000) {
						logLine = currentTimestamp + ",D," + localTimestamps.get(2) + "," + 
								+ localTimestamps.get(1) + "," + sequenceName + "\n";
						buffer = logLine.getBytes();
						if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
							scaleEventFileChannel.write(
									ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
							scaleEventFileOffset += buffer.length;
						}
					}
					Values latencyMetricTuple = new Values();
					latencyMetricTuple.add(SynefoConstant.OP_LATENCY_METRIC + "-" + sequenceName + 
							"#" + taskID + ":" + 
							OpLatencyState.s_3.toString() + ":" + 
							currentTimestamp);
					for(int i = 0; i < operator.getOutputSchema().size(); i++)
						latencyMetricTuple.add(null);
					for(Integer d_task : intActiveDownstreamTasks)
						collector.emitDirect(d_task, latencyMetricTuple);
					/**
					 * Calculate latency
					 */
					long latency = ( (localTimestamps.get(1) - localTimestamps.get(0) - (receivedTimestamps.get(1) - localTimestamps.get(0))) + 
							(localTimestamps.get(2) - receivedTimestamps.get(1) - (receivedTimestamps.get(2) - localTimestamps.get(1))) ) / 2;
//					latency = ( 
//							(localLatency[2] - localLatency[1] - (receivedLatency[2] - receivedLatency[1])) + 
//							(localLatency[1] - localLatency[0] - (receivedLatency[1] - receivedLatency[0]))
//							) / 2;
//					latency = ( 
//							Math.abs(localLatency[2] - localLatency[1] - 1000 - 
//									(receivedLatency[2] - receivedLatency[1] - 1000)) + 
//									Math.abs(localLatency[1] - localLatency[0] - 1000 - 
//											(receivedLatency[1] - receivedLatency[0] - 1000))
//							) / 2;
					logLine = currentTimestamp + ", " + latency + ",[" + localTimestamps.get(2) + "-" + localTimestamps.get(1) + "-" + localTimestamps.get(0) + 
							"-" + receivedTimestamps.get(2) + "-" + receivedTimestamps.get(1) + "-" + receivedTimestamps.get(0) + "]" + "\n";
					buffer = logLine.getBytes();
					if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
						scaleEventFileChannel.write(
								ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
						scaleEventFileOffset += buffer.length;
					}
					logLine = "received timestamps: " + receivedTimestamps.toString() + "\n";
					buffer = logLine.getBytes();
					if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
						scaleEventFileChannel.write(
								ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
						scaleEventFileOffset += buffer.length;
					}
					/**
					 * Cleanup timestamps and update task-Statistics
					 */
					receivedTimestampIndex.remove(sequenceName);
					localTimestampIndex.remove(sequenceName);
					statistics.updateWindowLatency(latency);
					
				}else {
					/**
					 * Something went wrong
					 */
				}
			}else {
				/**
				 * Need to disregard the message because its a duplicate
				 */
			}
		}else {
			/**
			 * New sequence arrived, need to initiate it
			 */
			if(receivedLatencyState == OpLatencyState.s_1) {
				sequenceInitiator.put(sequenceName, sequenceTokens[2]);
				sequenceStateIndex.put(sequenceName, OpLatencyState.r_1);
				receivedTimestamps = new ArrayList<Long>();
				receivedTimestamps.add(receivedTimestamp);
				receivedTimestampIndex.put(sequenceName, receivedTimestamps);
				localTimestamps = new ArrayList<Long>();
				localTimestamps.add(currentTimestamp);
				localTimestampIndex.put(sequenceName, localTimestamps);
				/**
				 * Need to forward the message accordingly, by updating only the second task-id and 
				 * the timestamp
				 */
				Values latencyMetricTuple = new Values();
				latencyMetricTuple.add(SynefoConstant.OP_LATENCY_METRIC + "-" + sequenceName + 
						"#" + taskID + ":" + 
						OpLatencyState.s_1.toString() + ":" + 
						currentTimestamp);
				for(int i = 0; i < operator.getOutputSchema().size(); i++)
					latencyMetricTuple.add(null);
				for(Integer d_task : intActiveDownstreamTasks)
					collector.emitDirect(d_task, latencyMetricTuple);
			}else {
				/**
				 * Do nothing, missed the sequence from its beginning
				 */
			}
		}
	}

	public void execute(Tuple tuple) {
		Long currentTimestamp = System.currentTimeMillis();
		/**
		 * If punctuation tuple is received:
		 * Perform Share of state and return execution
		 */
		if(tuple.getFields().contains("SYNEFO_HEADER") == false) {
			logger.error("+EFO-BOLT (" + taskName + ":" + taskID + "@" + taskIP + 
					") in execute(): received tuple with fields: " + tuple.getFields().toString() + 
					" from component: " + tuple.getSourceComponent() + " with task-id: " + tuple.getSourceTask());
			return;
		}
		String synefoHeader = tuple.getString(tuple.getFields().fieldIndex("SYNEFO_HEADER"));
		String opLatencyHeader = synefoHeader.split("-")[0];
		Long synefoTimestamp = null;
		if(synefoHeader != null && synefoHeader.equals("") == false) {
			if(synefoHeader.contains("/") && synefoHeader.contains(SynefoConstant.PUNCT_TUPLE_TAG) == true 
					&& synefoHeader.contains(SynefoConstant.ACTION_PREFIX) == true
					&& synefoHeader.contains(SynefoConstant.COMP_IP_TAG) == true) {
				String[] headerFields = synefoHeader.split("/");
				if(headerFields[0].equals(SynefoConstant.PUNCT_TUPLE_TAG)) {
					handlePunctuationTuple(tuple);
					collector.ack(tuple);
					return;
				}
			}else if(synefoHeader.contains(SynefoConstant.OP_LATENCY_METRIC) && 
					opLatencyHeader.equals(SynefoConstant.OP_LATENCY_METRIC)) {
				manageLatencySequence(synefoHeader, currentTimestamp);
				collector.ack(tuple);
				return;
			}else {
				synefoTimestamp = Long.parseLong(synefoHeader);
			}
		}
		/**
		 * Remove from both values and fields SYNEFO_HEADER (SYNEFO_TIMESTAMP)
		 */
		Values produced_values = null;
		Values values = new Values(tuple.getValues().toArray());
		/**
		 * Extract the timestamp given by the Spout
		 */
		values.remove(0);
		List<String> fieldList = tuple.getFields().toList();
		fieldList.remove(0);
		Fields fields = new Fields(fieldList);
		if(intActiveDownstreamTasks != null && intActiveDownstreamTasks.size() > 0) {
			List<Values> returnedTuples = null;
			Long executeStartTimestamp = System.currentTimeMillis();
			if(statOperatorFlag)
				returnedTuples = ((AbstractStatOperator) operator).execute(statistics, fields, values);
			else
				returnedTuples = operator.execute(fields, values);
			Long executeEndTimestamp = System.currentTimeMillis();
			statistics.updateWindowOperationalLatency((executeEndTimestamp - executeStartTimestamp));
			if(returnedTuples != null && returnedTuples.size() > 0) {
				for(Values v : returnedTuples) {
					produced_values = new Values();
					/**
					 * Retain the tuple's timestamp (timestamped from the Spout)
					 */
					produced_values.add(synefoTimestamp.toString());
					for(int i = 0; i < v.size(); i++) {
						produced_values.add(v.get(i));
					}
					collector.emitDirect(intActiveDownstreamTasks.get(downStreamIndex), produced_values);
				}
				if(downStreamIndex >= (intActiveDownstreamTasks.size() - 1)) {
					downStreamIndex = 0;
				}else {
					downStreamIndex += 1;
				}
			}
			statistics.updateSelectivity(( (double) returnedTuples.size() / 1.0));
			collector.ack(tuple);
		}else {
			List<Values> returnedTuples = null;
			Long executeStartTimestamp = System.currentTimeMillis();
			if(statOperatorFlag)
				returnedTuples = ((AbstractStatOperator) operator).execute(statistics, fields, values);
			else
				returnedTuples = operator.execute(fields, values);
			Long executeEndTimestamp = System.currentTimeMillis();
			/**
			 * Update the overall latency of the tuple (since this is a drain operator) 
			 */
			statistics.updateWindowLatency((currentTimestamp - synefoTimestamp));
			statistics.updateWindowOperationalLatency((executeEndTimestamp - executeStartTimestamp));
			statistics.updateSelectivity(( (double) returnedTuples.size() / 1.0));
			collector.ack(tuple);
		}
		statistics.updateMemory();
		statistics.updateCpuLoad();
		statistics.updateWindowThroughput();

		if(reportCounter >= SynefoBolt.statReportPeriod) {
			if(statOperatorFlag == false) {
				/**
				 * timestamp, cpu, memory, state-size, latency, operational-latency, throughput
				 */
				byte[] buffer = (currentTimestamp + "," + statistics.getCpuLoad() + "," + 
						statistics.getMemory() + ",N/A," + statistics.getWindowLatency() + "," + 
						statistics.getWindowOperationalLatency() + "," + 
						statistics.getWindowThroughput() + "\n").toString().getBytes();
				if(this.statisticFileChannel != null && this.statisticFileHandler != null) {
					statisticFileChannel.write(
							ByteBuffer.wrap(buffer), this.statisticFileOffset, "stat write", statisticFileHandler);
					statisticFileOffset += buffer.length;
				}
			}
			reportCounter = 0;
			if(warmFlag == false)
				warmFlag = true;
			statistics = new TaskStatistics(statReportPeriod);
		}else {
			reportCounter += 1;
		}

		if(autoScale && warmFlag == true)
			zooPet.setLatency(statistics.getWindowLatency());
		String scaleCommand = "";
		synchronized(zooPet) {
			if(zooPet.pendingCommands.isEmpty() == false) {
				scaleCommand = zooPet.returnScaleCommand();
			}
		}
		if(scaleCommand != null && scaleCommand.length() > 0) {
			String[] scaleCommandTokens = scaleCommand.split("[~@:]");
			String action = scaleCommandTokens[0];
			String taskWithIp = scaleCommandTokens[1] + ":" + scaleCommandTokens[2] + "@" + scaleCommandTokens[3];
			String taskIp = scaleCommandTokens[3];
			String task = scaleCommandTokens[1];
			Integer task_id = Integer.parseInt(scaleCommandTokens[2]);
			StringBuilder strBuild = new StringBuilder();
			strBuild.append(SynefoConstant.PUNCT_TUPLE_TAG + "/");
			downStreamIndex = 0;
			if(action.toLowerCase().contains("activate") || action.toLowerCase().contains("deactivate")) {
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + ") located scale-command: " + 
						scaleCommand + ", about to update routing tables (timestamp: " + currentTimestamp + ").");
				if(action.toLowerCase().equals("activate")) {
					if(activeDownstreamTasks.contains(taskWithIp) == false && intActiveDownstreamTasks.contains(task_id) == false) {
						if(activeDownstreamTasks.indexOf(taskWithIp) < 0)
							activeDownstreamTasks.add(taskWithIp);
						if(intActiveDownstreamTasks.indexOf(task_id) < 0)
							intActiveDownstreamTasks.add(task_id);
					}
				}else if(action.toLowerCase().equals("deactivate")) {
					if(activeDownstreamTasks.indexOf(taskWithIp) >= 0)
						activeDownstreamTasks.remove(activeDownstreamTasks.indexOf(taskWithIp));
					if(intActiveDownstreamTasks.indexOf(task_id) >= 0)
						intActiveDownstreamTasks.remove(intActiveDownstreamTasks.indexOf(task_id));
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + ") located scale-command: " + 
						scaleCommand + ", updated routing tables: " + intActiveDownstreamTasks + 
						"(timestamp: " + currentTimestamp + ").");
			}else {
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + ") located scale-command: " + 
						scaleCommand + ", about to produce punctuation tuple (timestamp: " + currentTimestamp + ").");
				if(action.toLowerCase().contains("add")) {
					if(activeDownstreamTasks.contains(taskWithIp) == false && intActiveDownstreamTasks.contains(task_id) == false) {
						if(activeDownstreamTasks.indexOf(taskWithIp) < 0)
							activeDownstreamTasks.add(taskWithIp);
						if(intActiveDownstreamTasks.indexOf(task_id) < 0)
							intActiveDownstreamTasks.add(task_id);
					}
					strBuild.append(SynefoConstant.ACTION_PREFIX + ":" + SynefoConstant.ADD_ACTION + "/");
				}else if(action.toLowerCase().contains("remove")) {
					strBuild.append(SynefoConstant.ACTION_PREFIX + ":" + SynefoConstant.REMOVE_ACTION + "/");
				}
				strBuild.append(SynefoConstant.COMP_TAG + ":" + task + ":" + task_id + "/");
				strBuild.append(SynefoConstant.COMP_NUM_TAG + ":" + intActiveDownstreamTasks.size() + "/");
				strBuild.append(SynefoConstant.COMP_IP_TAG + ":" + taskIp + "/");
				/**
				 * Populate other schema fields with null values, 
				 * after SYNEFO_HEADER
				 */
				Values punctValue = new Values();
				punctValue.add(strBuild.toString());
				for(int i = 0; i < operator.getOutputSchema().size(); i++) {
					punctValue.add(null);
				}
				for(Integer d_task : intActiveDownstreamTasks) {
					collector.emitDirect(d_task, punctValue);
				}
				/**
				 * In the case of removing a downstream task 
				 * we remove it after sending the punctuation tuples, so 
				 * that the removed task is notified to share state
				 */
				if(action.toLowerCase().contains("remove") && activeDownstreamTasks.indexOf(taskWithIp) >= 0) {
					activeDownstreamTasks.remove(activeDownstreamTasks.indexOf(taskWithIp));
					intActiveDownstreamTasks.remove(intActiveDownstreamTasks.indexOf(task_id));
				}
			}
			/**
			 * Re-initialize Operator-latency metrics
			 */
			reportCounter = 0;
		}
	}

	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		List<String> producerSchema = new ArrayList<String>();
		producerSchema.add("SYNEFO_HEADER");
		producerSchema.addAll(operator.getOutputSchema().toList());
		declarer.declare(new Fields(producerSchema));
	}

	@SuppressWarnings("unchecked")
	public void handlePunctuationTuple(Tuple tuple) {
		String scaleAction = null;
		String componentName = null;
		String componentId = null;
		Integer compNum = -1;
		String ip = null;
		logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
				") received punctuation tuple: " + tuple.toString() + 
				"(timestamp: " + System.currentTimeMillis() + ").");

		/**
		 * Expected Header format: 
		 * +EFO/ACTION:{ADD, REMOVE}/COMP:{taskName}:{taskID}/COMP_NUM:{Number of Components}/IP:{taskIP}/
		 */
		String[] tokens = ((String) tuple.getValues().get(0)).split("[/:]");
		scaleAction = tokens[2];
		componentName = tokens[4];
		componentId = tokens[5];
		compNum = Integer.parseInt(tokens[7]);
		ip = tokens[9];
		if(warmFlag == true)
			warmFlag = false;
		logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
				") action: " + scaleAction + ".");
		/**
		 * 
		 */
		byte[] buffer = ("timestamp: " + System.currentTimeMillis() + "," + scaleAction + "\n").toString().getBytes();
		if(this.scaleEventFileChannel != null && this.scaleEventFileHandler != null) {
			scaleEventFileChannel.write(
					ByteBuffer.wrap(buffer), this.scaleEventFileOffset, "stat write", scaleEventFileHandler);
			scaleEventFileOffset += buffer.length;
		}
		if(scaleAction != null && scaleAction.equals(SynefoConstant.ADD_ACTION)) {
			logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
					") received an ADD action (timestamp: " + System.currentTimeMillis() + ").");
			String selfComp = this.taskName + ":" + this.taskID;
			/**
			 * If this Synefobolt is about to become Active
			 */
			if(selfComp.equals(componentName + ":" + componentId)) {
				/**
				 * If statistics are reported to a database, add a data-point 
				 * with zero statistics
				 */
				if(statOperatorFlag == true) {
					((AbstractStatOperator) operator).reportStatisticBeforeScaleOut();
				}else {
					logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
							") timestamp: " + System.currentTimeMillis() + ", " + 
							"cpu: " + -1 + 
							", memory: " + -1 + 
							", latency: " + -1 + ", operational latency: " + -1 + 
							", throughput: " + -1);
					buffer = (System.currentTimeMillis() + "," + -1 + "," + 
							-1 + "," + -1 + "," + "," + -1 + "," + -1 + "\n").toString().getBytes();
					if(this.statisticFileChannel != null && this.statisticFileHandler != null) {
						statisticFileChannel.write(
								ByteBuffer.wrap(buffer), this.statisticFileOffset, "stat write", statisticFileHandler);
						statisticFileOffset += buffer.length;
					}
				}
				/**
				 * Re-initialize statistics object
				 */
				statistics = new TaskStatistics(statReportPeriod);
				/**
				 * If this component is added, open a ServerSocket
				 */
				try {
					ServerSocket _socket = new ServerSocket(6000 + taskID);
					int numOfStatesReceived = 0;
					logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
							") accepting connections to receive state... (IP:" + 
							_socket.getInetAddress().getHostAddress() + ", port: " + _socket.getLocalPort());
					boolean activeListFlag = false;
					while(numOfStatesReceived < (compNum - 1)) {
						Socket client = _socket.accept();
						ObjectOutputStream _stateOutput = new ObjectOutputStream(client.getOutputStream());
						ObjectInputStream _stateInput = new ObjectInputStream(client.getInputStream());
						Object responseObject = _stateInput.readObject();
						if(responseObject instanceof List) {
							List<Values> newState = (List<Values>) responseObject;
							operator.mergeState(operator.getOutputSchema(), newState);
						}
						if(activeListFlag == false) {
							_stateOutput.writeObject("+EFO_ACT_NODES");
							_stateOutput.flush();
							responseObject = _stateInput.readObject();
							if(responseObject instanceof List) {
								this.activeDownstreamTasks = (ArrayList<String>) responseObject;
								intActiveDownstreamTasks = new ArrayList<Integer>();
								Iterator<String> itr = activeDownstreamTasks.iterator();
								while(itr.hasNext()) {
									String[] downTask = itr.next().split("[:@]");
									Integer task = Integer.parseInt(downTask[1]);
									intActiveDownstreamTasks.add(task);
								}
								logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
										") received active downstream task list:" + activeDownstreamTasks + "(intActiveDownstreamTasks: " + 
										intActiveDownstreamTasks.toString() + ")");
								activeListFlag = true;
								downStreamIndex = 0;
							}

						}else {
							_stateOutput.writeObject("+EFO_ACK");
						}
						_stateOutput.flush();
						_stateInput.close();
						_stateOutput.close();
						client.close();
						numOfStatesReceived += 1;
					}
					_socket.close();
				} catch (IOException | ClassNotFoundException e) {
					e.printStackTrace();
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") Finished accepting connections to receive state (timestamp: " + System.currentTimeMillis() + ").");
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + ") routing table:" + 
						this.activeDownstreamTasks + " (timestamp: " + System.currentTimeMillis() + ").");
			}else {
				Socket client = new Socket();
				Integer comp_task_id = Integer.parseInt(componentId);
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") about to send state to about-to-be-added operator (IP: " + 
						ip + ", port: " + (6000 + comp_task_id) + ").");
				boolean attempt_flag = true;
				while (attempt_flag == true) {
					try {
						client = new Socket(ip, 6000 + comp_task_id);
						attempt_flag = false;
					} catch (IOException e) {
						logger.info("+EFO-BOLT (" + taskID + "): Connect failed (1), waiting and trying again");
						logger.info("+EFO-BOLT (" + taskID + "): " + e.getMessage());
						try
						{
							Thread.sleep(100);
						}
						catch(InterruptedException ie){
							ie.printStackTrace();
						}
					}
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") Connection established...");
				try {
					ObjectOutputStream _stateOutput = new ObjectOutputStream(client.getOutputStream());
					ObjectInputStream _stateInput = new ObjectInputStream(client.getInputStream());
					_stateOutput.writeObject(operator.getStateValues());
					_stateOutput.flush();
					Object responseObject = _stateInput.readObject();
					if(responseObject instanceof String) {
						String response = (String) responseObject;
						if(response.equals("+EFO_ACT_NODES")) {
							_stateOutput.writeObject(this.activeDownstreamTasks);
							_stateOutput.flush();
						}
					}
					_stateInput.close();
					_stateOutput.close();
					client.close();
				} catch (IOException | ClassNotFoundException e) {
					e.printStackTrace();
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") sent state to newly added node successfully (timestamp: " + System.currentTimeMillis() + ").");
			}
		}else if(scaleAction != null && scaleAction.equals(SynefoConstant.REMOVE_ACTION)) {
			logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
					") received a REMOVE action (timestamp: " + System.currentTimeMillis() + ").");
			String selfComp = this.taskName + ":" + this.taskID;
			if(selfComp.equals(componentName + ":" + componentId)) {
				/**
				 * If statistics are reported to a database, add a data-point 
				 * with zero statistics
				 */
				if(statOperatorFlag == true)
					((AbstractStatOperator) operator).reportStatisticBeforeScaleOut();
				else
					logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
							") timestamp: " + System.currentTimeMillis() + ", " + 
							"cpu: " + -1 + 
							", memory: " + -1 + 
							", latency: " + -1 + 
							", operational-latency: " + -1 + 
							", throughput: " + -1);
				/**
				 * Re-initialize statistics object
				 */
				statistics = new TaskStatistics(statReportPeriod);
				try {
					ServerSocket _socket = new ServerSocket(6000 + taskID);
					logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
							") accepting connections to send state... (IP:" + 
							_socket.getInetAddress() + ", port: " + _socket.getLocalPort());
					int numOfStatesReceived = 0;
					while(numOfStatesReceived < (compNum - 1)) {
						Socket client = _socket.accept();
						ObjectOutputStream _stateOutput = new ObjectOutputStream(client.getOutputStream());
						ObjectInputStream _stateInput = new ObjectInputStream(client.getInputStream());
						_stateOutput.writeObject(operator.getStateValues());
						_stateOutput.flush();
						Object responseObject = _stateInput.readObject();
						if(responseObject instanceof String) {
							String response = (String) responseObject;
							if(response.equals("+EFO_ACK")) {

							}
						}
						_stateInput.close();
						_stateOutput.close();
						client.close();
						numOfStatesReceived += 1;
					}
					_socket.close();
				} catch (IOException | ClassNotFoundException e) {
					e.printStackTrace();
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") Finished accepting connections to send state (timestamp: " + 
						System.currentTimeMillis() + ").");
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") routing table:" + this.activeDownstreamTasks + " (timestamp: " + System.currentTimeMillis() + ").");
			}else {
				Socket client = new Socket();
				Integer comp_task_id = Integer.parseInt(componentId);
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") about to receive state from about-to-be-removed operator (IP: " + ip + 
						", port: " + (6000 + comp_task_id) + ").");
				boolean attempt_flag = true;
				while (attempt_flag == true) {
					try {
						client = new Socket(ip, 6000 + comp_task_id);
						attempt_flag = false;
					} catch (IOException e) {
						logger.info("+EFO-BOLT (" + taskID + "): Connect failed (2), waiting and trying again");
						logger.info("+EFO-BOLT (" + taskID + "): " + e.getMessage());
						try
						{
							Thread.sleep(100);
						}
						catch(InterruptedException ie){
							ie.printStackTrace();
						}
					}
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + 
						this.taskIP + ") Connection established (timestamp: " + System.currentTimeMillis() + ").");
				try {
					ObjectOutputStream _stateOutput = new ObjectOutputStream(client.getOutputStream());
					ObjectInputStream _stateInput = new ObjectInputStream(client.getInputStream());
					Object responseObject = _stateInput.readObject();
					if(responseObject instanceof List) {
						List<Values> newState = (List<Values>) responseObject;
						operator.mergeState(operator.getOutputSchema(), newState);
					}
					_stateOutput.writeObject("+EFO_ACK");
					_stateOutput.flush();
					_stateInput.close();
					_stateOutput.close();
					client.close();
				} catch (IOException | ClassNotFoundException e) {
					e.printStackTrace();
				}
				logger.info("+EFO-BOLT (" + this.taskName + ":" + this.taskID + "@" + this.taskIP + 
						") received state from about-to-be-removed node successfully (timestamp: " + System.currentTimeMillis() + ").");
			}
		}
		zooPet.resetSubmittedScaleFlag();
		reportCounter = 0;
		/**
		 * Re-initialize operator-latency metrics
		 */
	}

}
