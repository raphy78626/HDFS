package TaskTracker;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import JobTracker.IJobTracker;
import Protobuf.MapReduceProtobuf.BlockLocations;
import Protobuf.MapReduceProtobuf.HeartBeatRequest;
import Protobuf.MapReduceProtobuf.HeartBeatResponse;
import Protobuf.MapReduceProtobuf.MapTaskInfo;
import Protobuf.MapReduceProtobuf.MapTaskStatus;
import Protobuf.MapReduceProtobuf.ReduceTaskInfo;
import Protobuf.MapReduceProtobuf.ReduceTaskStatus;

import com.google.protobuf.InvalidProtocolBufferException;

public class TaskTracker {

	private static final String configurationFile = "Resources/tasktracker.properties";
	private static Integer exitTimeout;
	private static Integer heartBeatTimeout;
	private static Integer taskTrackerID;
	private static String jobTrackerLocation;
	private static Integer mapSlots;
	private static Integer reduceSlots;
	private static IJobTracker jobTracker;
	private static HashMap<Integer, MapTaskStatus.Builder> runningMapTasks;
	private static HashMap<Integer, ReduceTaskStatus.Builder> runningReduceTasks;

	private static String networkInterface;

	private static ThreadPoolExecutor executor;

	public static void main(String[] args) throws IOException, NotBoundException {

		if (args.length != 1) {
			System.err.println("USAGE: java TaskTracker.TaskTracker <serverID>");
			System.exit(-1);
		}

		taskTrackerID = Integer.parseInt(args[0]);

		Properties properties = new Properties();
		InputStream inputStream = new FileInputStream(configurationFile);
		properties.load(inputStream);

		runningMapTasks = new HashMap<Integer, MapTaskStatus.Builder>();
		runningReduceTasks = new HashMap<Integer, ReduceTaskStatus.Builder>();

		networkInterface = properties.getProperty("Network Interface");
		exitTimeout = Integer.parseInt(properties.getProperty("Exit Timeout"));
		mapSlots = Integer.parseInt(properties.getProperty("Map Slots"));
		reduceSlots = Integer.parseInt(properties.getProperty("Reduce Slots"));
		jobTrackerLocation = properties.getProperty("JobTracker Location");
		heartBeatTimeout = Integer.parseInt(properties.getProperty("HeartBeat Timeout"));

		if ((networkInterface == null) || (exitTimeout == null) || (jobTrackerLocation == null) || (mapSlots == null) || (reduceSlots == null)) {
			System.out.println("Configuration Missing...");
			System.exit(-1);
		}

		jobTracker = (IJobTracker) LocateRegistry.getRegistry(jobTrackerLocation, Registry.REGISTRY_PORT).lookup("JobTracker");
		executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

			@Override
			public void run() {
				executor.shutdown();
				try {
					executor.awaitTermination(exitTimeout, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					System.out.println("Task Inturrupted...");
					System.exit(-1);
				}
			}
		}));

		executor.execute(new Runnable() {

			private ArrayList<Future<String[]>> mapTaskOutputs;
			private ArrayList<Future<String[]>> reduceTaskOutputs;

			@Override
			public void run() {
				this.mapTaskOutputs = new ArrayList<Future<String[]>>();
				this.reduceTaskOutputs = new ArrayList<Future<String[]>>();
				while (true) {
					ArrayList<MapTaskStatus> localCopyRunningMapTasks = new ArrayList<MapTaskStatus>();
					for (MapTaskStatus.Builder tempBuilder : runningMapTasks.values()) {
						localCopyRunningMapTasks.add(tempBuilder.build());
					}
					ArrayList<ReduceTaskStatus> localCopyRunningReduceTasks = new ArrayList<ReduceTaskStatus>();
					for (ReduceTaskStatus.Builder tempBuilder : runningReduceTasks.values()) {
						localCopyRunningReduceTasks.add(tempBuilder.build());
					}

					for (MapTaskStatus tempMapTaskStatus : localCopyRunningMapTasks) {
						if (tempMapTaskStatus.getTaskCompleted() == true) {

							System.out.println("Map Task Completed");

							runningMapTasks.remove(tempMapTaskStatus.getTaskId());
						}
					}

					for (ReduceTaskStatus tempReduceTaskStatus : localCopyRunningReduceTasks) {
						if (tempReduceTaskStatus.getTaskCompleted() == true) {

							System.out.println("Reduce Task Completed");

							runningReduceTasks.remove(tempReduceTaskStatus.getTaskId());
						}
					}

					Integer mapSlotsFree = ((mapSlots - localCopyRunningMapTasks.size()) > 0) ? mapSlots - localCopyRunningMapTasks.size() : 0;
					Integer reduceSlotsFree = ((reduceSlots - localCopyRunningReduceTasks.size()) > 0) ? reduceSlots - localCopyRunningReduceTasks.size() : 0;

					HeartBeatResponse heartbeatResponse = null;
					try {
						heartbeatResponse = HeartBeatResponse.parseFrom(jobTracker.heartBeat(HeartBeatRequest.newBuilder().setTaskTrackerId(taskTrackerID).setNumMapSlotsFree(mapSlotsFree).setNumReduceSlotsFree(reduceSlotsFree).addAllMapStatus(localCopyRunningMapTasks).addAllReduceStatus(localCopyRunningReduceTasks).build().toByteArray()));
					} catch (RemoteException | InvalidProtocolBufferException e) {
						e.printStackTrace();
						System.exit(-1);
					}
					if (heartbeatResponse.getStatus() == 0) {
						System.err.println("HeartBeat failed");
						System.exit(-1);
					}

					for (MapTaskInfo tempMapTask : heartbeatResponse.getMapTasksList()) {
						for (BlockLocations tempBlockLocations : tempMapTask.getInputBlocksList()) {
							this.mapTaskOutputs.add(executor.submit(new MapTask(tempBlockLocations.getBlockNumber(), tempMapTask.getMapName(), tempMapTask.getTaskId())));
						}

						System.out.println("Map Task Received");

						runningMapTasks.put(tempMapTask.getTaskId(), MapTaskStatus.newBuilder().setJobId(tempMapTask.getJobId()).setTaskId(tempMapTask.getTaskId()));
					}

					for (ReduceTaskInfo tempReduceTask : heartbeatResponse.getReduceTasksList()) {
						this.reduceTaskOutputs.add(executor.submit(new ReduceTask(tempReduceTask.getMapOutputFilesList(), tempReduceTask.getReducerName(), tempReduceTask.getOutputFile(), tempReduceTask.getTaskId())));

						System.out.println("Reduce Task Received");

						runningReduceTasks.put(tempReduceTask.getTaskId(), ReduceTaskStatus.newBuilder().setJobId(tempReduceTask.getJobId()).setTaskId(tempReduceTask.getTaskId()));
					}

					Iterator<Future<String[]>> iterator;

					iterator = this.mapTaskOutputs.iterator();
					while (iterator.hasNext()) {
						Future<String[]> data = iterator.next();
						if (data.isDone()) {
							String[] innerData = new String[2];
							try {
								innerData = data.get();
							} catch (InterruptedException | ExecutionException e) {
								e.printStackTrace();
							}
							runningMapTasks.get(Integer.parseInt(innerData[0])).setTaskCompleted(true).setMapOutputFile(innerData[1]);
							iterator.remove();
						} else {
							continue;
						}
					}

					iterator = this.reduceTaskOutputs.iterator();
					while (iterator.hasNext()) {
						Future<String[]> data = iterator.next();
						if (data.isDone()) {
							String[] innerData = new String[1];
							try {
								innerData = data.get();
							} catch (InterruptedException | ExecutionException e) {
								e.printStackTrace();
							}
							runningReduceTasks.get(Integer.parseInt(innerData[0])).setTaskCompleted(true);
							iterator.remove();
						} else {
							continue;
						}
					}

					try {
						Thread.sleep(heartBeatTimeout);
					} catch (InterruptedException e) {
						// nope
					}
				}
			}
		});

		System.out.println("Loaded TaskTracker...");
	}

	public TaskTracker() {
		super();
	}

}
