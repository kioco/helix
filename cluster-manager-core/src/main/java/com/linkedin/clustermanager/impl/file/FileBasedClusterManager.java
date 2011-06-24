package com.linkedin.clustermanager.impl.file;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.linkedin.clustermanager.controller.IdealStateCalculatorByShuffling;
import com.linkedin.clustermanager.core.ClusterDataAccessor;
import com.linkedin.clustermanager.core.ClusterManager;
import com.linkedin.clustermanager.core.InstanceType;
import com.linkedin.clustermanager.core.NotificationContext;
import com.linkedin.clustermanager.core.ClusterDataAccessor.ClusterPropertyType;
import com.linkedin.clustermanager.core.ClusterDataAccessor.InstancePropertyType;
import com.linkedin.clustermanager.core.listeners.ConfigChangeListener;
import com.linkedin.clustermanager.core.listeners.CurrentStateChangeListener;
import com.linkedin.clustermanager.core.listeners.ExternalViewChangeListener;
import com.linkedin.clustermanager.core.listeners.IdealStateChangeListener;
import com.linkedin.clustermanager.core.listeners.LiveInstanceChangeListener;
import com.linkedin.clustermanager.core.listeners.MessageListener;
import com.linkedin.clustermanager.model.ClusterView;
import com.linkedin.clustermanager.model.IdealState;
import com.linkedin.clustermanager.model.Message;
import com.linkedin.clustermanager.model.ZNRecord;
import com.linkedin.clustermanager.tools.ClusterViewSerializer;

public class FileBasedClusterManager implements ClusterManager {
	private ClusterView _clusterView;
	private ClusterDataAccessor _fileDataAccessor;
	private final String _clusterName;
	private final InstanceType _instanceType;
	private final String _staticClusterConfigFile;
	private final String _instanceName;
	private boolean _isConnected;

	public static final String _sessionId = "12345";
	public static final String configFile = "configFile";

	public FileBasedClusterManager(String clusterName, String instanceName,
			InstanceType instanceType, String staticClusterConfigFile) {
		this._clusterName = clusterName;
		this._instanceName = instanceName;
		this._instanceType = instanceType;
		this._staticClusterConfigFile = staticClusterConfigFile;

		this._clusterView = readClusterView(staticClusterConfigFile);

		_fileDataAccessor = new FileBasedDataAccessor();

	}

	private static Message createSimpleMessage(ZNRecord idealStateRecord,
			String stateUnitKey, String instanceName, String currentState,
			String nextState) {
		Message message = new Message();
		String uuid = UUID.randomUUID().toString();
		message.setId(uuid);
		message.setMsgId(uuid);
		String hostName = "UNKNOWN";
		try {
			hostName = InetAddress.getLocalHost().getCanonicalHostName();
		} catch (UnknownHostException e) {
			// logger.info("Unable to get Host name. Will set it to UNKNOWN, mostly ignorable",
			// e);
			// can ignore it,
		}
		message.setSrcName(hostName);
		message.setTgtName(instanceName);
		message.setMsgState("new");
		message.setStateUnitKey(stateUnitKey);
		message.setStateUnitGroup(idealStateRecord.getId());
		message.setFromState(currentState);
		message.setToState(nextState);

		// String sessionId =
		// _liveInstanceDataHolder.getSessionId(instanceName);
		// message.setTgtSessionId(sessionId);
		message.setTgtSessionId(FileBasedClusterManager._sessionId);
		return message;
	}

	private static List<Message> computeMessagesForSimpleTransition(
			IdealState idealState, ZNRecord idealStateRecord) {
		List<Message> messages = new ArrayList<Message>();
		// System.out.println(idealStateRecord.getId());

		for (String stateUnitKey : idealState.stateUnitSet()) {
			Map<String, String> instanceStateMap;
			instanceStateMap = idealState.getInstanceStateMap(stateUnitKey);
			for (String instanceName : instanceStateMap.keySet()) {
				String desiredState = idealState
						.get(stateUnitKey, instanceName);

				if (desiredState.equals("MASTER")) {
					Message message = createSimpleMessage(idealStateRecord,
							stateUnitKey, instanceName, "OFFLINE", "SLAVE");
					messages.add(message);
					message = createSimpleMessage(idealStateRecord,
							stateUnitKey, instanceName, "SLAVE", "MASTER");
					messages.add(message);
				} else {
					Message message = createSimpleMessage(idealStateRecord,
							stateUnitKey, instanceName, "OFFLINE", "SLAVE");
					messages.add(message);
				}

			}
		}

		return messages;
	}

	public static class DBParam {
		public String name;
		public int partitions;

		public DBParam(String n, int p) {
			name = n;
			partitions = p;
		}
	}

	public static ClusterView generateStaticConfigClusterView(
			String[] nodesInfo, List<DBParam> dbParams, int replicas) {
		// create fake cluster view
		ClusterView view = new ClusterView();

		// add nodes
		List<ZNRecord> nodeConfigList = new ArrayList<ZNRecord>();
		List<String> instanceNames = new ArrayList<String>();

		for (String nodeInfo : nodesInfo) {
			int lastPos = nodeInfo.lastIndexOf(":");
			String host = nodeInfo.substring(0, lastPos);
			String port = nodeInfo.substring(lastPos + 1);
			ZNRecord nodeConfig = new ZNRecord();

			String nodeId = host + "_" + port;
			nodeConfig.setId(nodeId);
			instanceNames.add(nodeId);
			nodeConfig.setSimpleField("HOST", host);
			nodeConfig.setSimpleField("PORT", "" + port);
			nodeConfigList.add(nodeConfig);
		}

		view.setClusterPropertyList(ClusterPropertyType.CONFIGS, nodeConfigList);

		// compute ideal states for each db
		List<ZNRecord> idealStates = new ArrayList<ZNRecord>();
		for (DBParam dbParam : dbParams) {
			ZNRecord result = IdealStateCalculatorByShuffling
					.calculateIdealState(instanceNames, dbParam.partitions,
							replicas, dbParam.name);

			idealStates.add(result);
		}
		view.setClusterPropertyList(ClusterPropertyType.IDEALSTATES,
				idealStates);

		// calculate messages for transition using naive algorithm
		Map<String, List<ZNRecord>> msgListForInstance = new HashMap<String, List<ZNRecord>>();
		List<ZNRecord> idealStatesArray = view
				.getClusterPropertyList(ClusterPropertyType.IDEALSTATES);
		for (ZNRecord idealStateRecord : idealStatesArray) {
			IdealState idealState = new IdealState(idealStateRecord);

			List<Message> messages = computeMessagesForSimpleTransition(
					idealState, idealStateRecord);

			for (Message message : messages) {
				// logger.info("Sending message to " + message.getTgtName() +
				// " transition "
				// + message.getStateUnitKey() + " from:" +
				// message.getFromState() +
				// " to:"
				// + message.getToState());
				// client.addMessage(message, message.getTgtName());
				String instance = message.getTgtName();
				List<ZNRecord> msgList = msgListForInstance.get(instance);
				if (msgList == null) {
					msgList = new ArrayList<ZNRecord>();
					msgListForInstance.put(instance, msgList);
				}
				msgList.add(message);
			}
		}

		// put message lists into cluster view
		List<ClusterView.MemberInstance> insList = new ArrayList<ClusterView.MemberInstance>();
		for (Map.Entry<String, List<ZNRecord>> entry : msgListForInstance
				.entrySet()) {
			String instance = entry.getKey();
			List<ZNRecord> msgList = entry.getValue();

			ClusterView.MemberInstance ins = view.getMemberInstance(instance,
					true);
			ins.setInstanceProperty(InstancePropertyType.MESSAGES, msgList);
			insList.add(ins);
		}

		view.setInstances(insList);

		return view;
	}

	public static ClusterView readClusterView(String file) {
		ClusterViewSerializer serializer = new ClusterViewSerializer(file);
		ClusterView restoredView = (ClusterView) serializer.deserialize(null);

		return restoredView;
	}

	@Override
	public void disconnect() {
		_isConnected = false;
	}

	@Override
	public void addIdealStateChangeListener(IdealStateChangeListener listener) {
		NotificationContext context = new NotificationContext(this);
		context.setType(NotificationContext.Type.INIT);
		listener.onIdealStateChange(this._clusterView
				.getClusterPropertyList(ClusterPropertyType.IDEALSTATES),
				context);
	}

	@Override
	public void addLiveInstanceChangeListener(
			LiveInstanceChangeListener listener) {
		throw new UnsupportedOperationException(
				"addLiveInstanceChangeListener is not supported by File Based cluster manager");
	}

	@Override
	public void addConfigChangeListener(ConfigChangeListener listener) {
		throw new UnsupportedOperationException(
				"addConfigChangeListener is not supported by File Based cluster manager");
	}

	@Override
	public void addMessageListener(MessageListener listener, String instanceName) {
		NotificationContext context = new NotificationContext(this);
		context.setType(NotificationContext.Type.INIT);
		List<ZNRecord> messages;
		messages = _clusterView.getMemberInstance(instanceName, true)
				.getInstanceProperty(InstancePropertyType.MESSAGES);
		// System.out.println("instance name: " + instanceName);
		// System.out.println("messages: " + messages);

		listener.onMessage(instanceName, messages, context);

	}

	@Override
	public void addCurrentStateChangeListener(
			CurrentStateChangeListener listener, String instanceName) {
		throw new UnsupportedOperationException(
				"addCurrentStateChangeListener is not supported by File Based cluster manager");
	}

	@Override
	public void addExternalViewChangeListener(
			ExternalViewChangeListener listener) {
		throw new UnsupportedOperationException(
				"addExternalViewChangeListener is not supported by File Based cluster manager");
	}

	@Override
	public ClusterDataAccessor getDataAccessor() {
		return _fileDataAccessor;
	}

	@Override
	public String getClusterName() {
		return _clusterName;
	}

	@Override
	public String getInstanceName() {
		return _instanceName;
	}

	@Override
	public void connect() {
		_isConnected = true;
	}

	@Override
	public String getSessionId() {
		return _sessionId;
	}

	private static Options constructCommandLineOptions() {
		Option fileOption = OptionBuilder.withLongOpt(configFile)
				.withDescription("Provide file to write states/messages")
				.create();
		fileOption.setArgs(1);
		fileOption.setRequired(true);
		fileOption.setArgName("File to read states/messages (Required)");

		Options options = new Options();
		options.addOption(fileOption);
		return options;

	}

	public static CommandLine processCommandLineArgs(String[] cliArgs)
			throws Exception {
		CommandLineParser cliParser = new GnuParser();
		Options cliOptions = constructCommandLineOptions();
		CommandLine cmd = null;

		try {
			return cliParser.parse(cliOptions, cliArgs);
		} catch (ParseException pe) {
			System.err
					.println("CommandLineClient: failed to parse command-line options: "
							+ pe.toString());
			// printUsage(cliOptions);
			System.exit(1);
		}
		return null;
	}

	public static void main(String[] args) throws Exception {
		// for temporary test only
		System.out.println("Generate static config file for cluster");
		String file = "/tmp/cluster-12345.cv";

		CommandLine cmd = processCommandLineArgs(args);
		file = cmd.getOptionValue(configFile);

		// create fake db names & nodes info
		List<FileBasedClusterManager.DBParam> dbParams = new ArrayList<FileBasedClusterManager.DBParam>();
		dbParams.add(new FileBasedClusterManager.DBParam("BizFollow", 1));
		dbParams.add(new FileBasedClusterManager.DBParam("BizProfile", 1));
		dbParams.add(new FileBasedClusterManager.DBParam("EspressoDB", 10));
		dbParams.add(new FileBasedClusterManager.DBParam("MailboxDB", 128));
		dbParams.add(new FileBasedClusterManager.DBParam("MyDB", 8));
		dbParams.add(new FileBasedClusterManager.DBParam("schemata", 1));

		String[] nodesInfo = { "localhost:8900", "localhost:8901",
				"localhost:8902", "localhost:8903", "localhost:8904" };

		ClusterViewSerializer serializer = new ClusterViewSerializer(file);
		ClusterView view = generateStaticConfigClusterView(nodesInfo, dbParams,
				0);

		byte[] bytes;
		bytes = serializer.serialize(view);
		// System.out.println(new String(bytes));

		ClusterView restoredView = (ClusterView) serializer.deserialize(bytes);
		// System.out.println(restoredView);

		bytes = serializer.serialize(restoredView);
		System.out.println(new String(bytes));

	}

	@Override
	public boolean isConnected() {
		return _isConnected;
	}
}
