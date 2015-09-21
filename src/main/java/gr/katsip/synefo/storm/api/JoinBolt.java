package gr.katsip.synefo.storm.api;

import backtype.storm.metric.api.AssignableMetric;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import gr.katsip.synefo.storm.lib.SynefoMessage;
import gr.katsip.synefo.storm.operators.relational.elastic.NewJoinJoiner;
import gr.katsip.synefo.utils.SynefoConstant;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by katsip on 9/21/2015.
 */
public class JoinBolt extends BaseRichBolt {

    private static final int METRIC_REPORT_FREQ_SEC = 5;

    private static final int WARM_UP_THRESHOLD = 10000;

    private String taskName;

    private String synefoAddress;

    private Integer synefoPort;

    private NewJoinJoiner joiner;

    private String zookeeperAddress;

    private boolean AUTO_SCALE;

    private String taskAddress;

    private OutputCollector outputCollector;

    private Integer taskIdentifier;

    private Integer workerPort;

    private ZooPet zookeeperClient;

    private List<String> downstreamTaskNames;

    private List<Integer> downstreamTaskIdentifiers;

    private List<String> activeDownstreamTaskNames;

    private List<Integer> activeDownstreamTaskIdentifiers;

    private int tupleCounter;

    private boolean SYSTEM_WARM_FLAG;

    private Integer downstreamIndex;

    private transient AssignableMetric latency;

    private transient AssignableMetric throughput;

    private transient AssignableMetric executeLatency;

    private transient AssignableMetric stateSize;

    public JoinBolt(String taskName, String synefoAddress, Integer synefoPort,
                    NewJoinJoiner joiner, String zookeeperAddress, boolean AUTO_SCALE) {
        this.taskName = taskName;
        this.synefoAddress = synefoAddress;
        this.synefoPort = synefoPort;
        this.joiner = joiner;
        this.zookeeperAddress = zookeeperAddress;
        this.AUTO_SCALE = AUTO_SCALE;
        downstreamTaskNames = null;
        activeDownstreamTaskNames = null;
        tupleCounter = 0;
        SYSTEM_WARM_FLAG = false;
    }

    public void register() {
        Socket socket;
        ObjectOutputStream output;
        ObjectInputStream input;
        SynefoMessage message = new SynefoMessage();
        message._type = SynefoMessage.Type.REG;
        message._values.put("TASK_IP", taskAddress);
        message._values.put("TASK_TYPE", "JOIN_BOLT");
        message._values.put("JOIN_STEP", joiner.operatorStep());
        message._values.put("JOIN_RELATION", joiner.relationStorage());
        message._values.put("TASK_NAME", taskName);
        message._values.put("TASK_ID", Integer.toString(taskIdentifier));
        message._values.put("WORKER_PORT", Integer.toString(workerPort));
        try {
            socket = new Socket(synefoAddress, synefoPort);
            output = new ObjectOutputStream(socket.getOutputStream());
            input = new ObjectInputStream(socket.getInputStream());
            output.writeObject(message);
            output.flush();
            message = null;
            ArrayList<String> downstream = null;
            try {
                downstream = (ArrayList<String>) input.readObject();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            if(downstream != null && downstream.size() > 0) {
                downstreamTaskNames = new ArrayList<String>(downstream);
                downstreamTaskIdentifiers = new ArrayList<Integer>();
                Iterator<String> itr = downstreamTaskNames.iterator();
                while(itr.hasNext()) {
                    String[] tokens = itr.next().split("[:@]");
                    Integer task = Integer.parseInt(tokens[1]);
                    downstreamTaskIdentifiers.add(task);
                }
            }else {
                downstreamTaskNames = new ArrayList<String>();
                downstreamTaskIdentifiers = new ArrayList<Integer>();
            }
            ArrayList<String> activeDownstream = null;
            try {
                activeDownstream = (ArrayList<String>) input.readObject();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            if(activeDownstream != null && activeDownstream.size() > 0) {
                activeDownstreamTaskNames = new ArrayList<String>(activeDownstream);
                activeDownstreamTaskIdentifiers = new ArrayList<Integer>();
                Iterator<String> itr = activeDownstreamTaskNames.iterator();
                while(itr.hasNext()) {
                    String[] tokens = itr.next().split("[:@]");
                    Integer task = Integer.parseInt(tokens[1]);
                    activeDownstreamTaskIdentifiers.add(task);
                }
            }else {
                activeDownstreamTaskNames = new ArrayList<String>();
                activeDownstreamTaskIdentifiers = new ArrayList<Integer>();
            }
            output.close();
            input.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        zookeeperClient.start();
        zookeeperClient.getScaleCommand();
        downstreamIndex = new Integer(0);
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.outputCollector = outputCollector;
        taskIdentifier = topologyContext.getThisTaskId();
        workerPort = topologyContext.getThisWorkerPort();
        taskName = taskName + "_" + taskIdentifier;
        try {
            taskAddress = InetAddress.getLocalHost().getHostAddress();
        } catch(UnknownHostException e) {
            e.printStackTrace();
        }
        zookeeperClient = new ZooPet(zookeeperAddress, taskName, taskIdentifier, taskAddress);
        if(downstreamTaskNames == null && activeDownstreamTaskNames == null)
            register();
        initMetrics(topologyContext);
        SYSTEM_WARM_FLAG = false;
        tupleCounter = 0;
    }

    private void initMetrics(TopologyContext context) {
        latency = new AssignableMetric(null);
        throughput = new AssignableMetric(null);
        executeLatency = new AssignableMetric(null);
        stateSize = new AssignableMetric(null);
        context.registerMetric("latency", latency, JoinBolt.METRIC_REPORT_FREQ_SEC);
        context.registerMetric("execute-latency", executeLatency, JoinBolt.METRIC_REPORT_FREQ_SEC);
        context.registerMetric("throughput", throughput, JoinBolt.METRIC_REPORT_FREQ_SEC);
        context.registerMetric("state-size", stateSize, JoinBolt.METRIC_REPORT_FREQ_SEC);
    }

    private boolean isScaleHeader(String header) {
        return (header.contains(SynefoConstant.PUNCT_TUPLE_TAG) == true &&
                header.contains(SynefoConstant.ACTION_PREFIX) == true &&
                header.contains(SynefoConstant.COMP_IP_TAG) == true &&
                header.split("/")[0].equals(SynefoConstant.PUNCT_TUPLE_TAG));
    }

    @Override
    public void execute(Tuple tuple) {
        String header = "";
        if (!tuple.getFields().contains("SYNEFO_HEADER")) {
            outputCollector.fail(tuple);
            return;
        }else {
            header = tuple.getString(tuple.getFields().fieldIndex("SYNEFO_HEADER"));
            if (header != null && !header.equals("") && header.contains("/") && isScaleHeader(header)) {
                /**
                 * TODO: Finish up the handle punctuation tuple
                 */
                outputCollector.ack(tuple);
                return;
            }
        }
        Values values = new Values(tuple.getValues().toArray());
        values.remove(0);
        List<String> fieldList = tuple.getFields().toList();
        fieldList.remove(0);
        Fields fields = new Fields(fieldList);
        long startTime = System.currentTimeMillis();
        if (activeDownstreamTaskIdentifiers.size() > 0) {
            joiner.execute(tuple, outputCollector, activeDownstreamTaskIdentifiers,
                    downstreamIndex, fields, values, null);
            outputCollector.ack(tuple);
        }else {
            joiner.execute(tuple, outputCollector, activeDownstreamTaskIdentifiers,
                    downstreamIndex, fields, values, null);
            outputCollector.ack(tuple);
        }
        long endTime = System.currentTimeMillis();

        tupleCounter++;
        if (tupleCounter >= WARM_UP_THRESHOLD && !SYSTEM_WARM_FLAG)
            SYSTEM_WARM_FLAG = true;
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        List<String> producerSchema = new ArrayList<String>();
        producerSchema.add("SYNEFO_HEADER");
        producerSchema.addAll(joiner.getOutputSchema().toList());
        outputFieldsDeclarer.declare(new Fields(producerSchema));
    }
}
