package NameNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import Protobuf.HDFSProtobuf.BlockReportRequest;
import Protobuf.HDFSProtobuf.BlockReportResponse;
import Protobuf.HDFSProtobuf.CloseFileRequest;
import Protobuf.HDFSProtobuf.CloseFileResponse;
import Protobuf.HDFSProtobuf.DataNodeLocation;
import Protobuf.HDFSProtobuf.HeartBeatRequest;
import Protobuf.HDFSProtobuf.HeartBeatResponse;
import Protobuf.HDFSProtobuf.ListFilesResponse;
import Protobuf.HDFSProtobuf.OpenFileRequest;
import Protobuf.HDFSProtobuf.OpenFileResponse;

import com.google.protobuf.InvalidProtocolBufferException;

public class NameNode extends UnicastRemoteObject implements INameNode {

	private static final long serialVersionUID = 1L;
	private static final String configurationFile = "Resources/namenode.properties";
	private static HashMap<String, Integer> fileNameHandleMap;
	private static HashMap<Integer, ArrayList<Integer>> handleBlockIDMap;
	private static HashMap<Integer, HashSet<DataNodeLocation>> blockIDLocationMap;
	private static HashMap<Integer, DataNodeLocation> livingDataNodes;
	private static File dataFile;
	private static Integer commitTimeout;
	private static Integer handleID = 0;

	private static void commitData() throws IOException {
		StringBuilder stringBuilder = new StringBuilder();
		for (String tempfileNameString : fileNameHandleMap.keySet()) {
			stringBuilder.append(tempfileNameString);
			stringBuilder.append("--");
			String separator = "";
			Integer handle = fileNameHandleMap.get(tempfileNameString);
			for (Integer tempInteger : handleBlockIDMap.get(handle)) {
				stringBuilder.append(separator).append(Integer.toString(tempInteger));
				separator = ",";
			}
			stringBuilder.append("\n");
		}
		BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(dataFile));
		bufferedWriter.write(stringBuilder.toString());
		bufferedWriter.close();
	}

	private static Integer getNewHandleID() {
		handleID++;
		return new Integer(handleID);
	}

	private static void loadData() throws IOException {
		BufferedReader bufferedReader = new BufferedReader(new FileReader(dataFile));
		for (String tempLine; (tempLine = bufferedReader.readLine()) != null;) {
			Integer handle = getNewHandleID();
			String[] splitStrings = tempLine.split("--", 2);
			String fileName = splitStrings[0];
			ArrayList<Integer> blockNumbers = new ArrayList<Integer>();

			if (!splitStrings[1].equals("")) {
				for (String tempString : splitStrings[1].split(",")) {
					blockNumbers.add(Integer.parseInt(tempString));
				}
			}
			fileNameHandleMap.put(fileName, handle);
			handleBlockIDMap.put(handle, blockNumbers);
		}
		bufferedReader.close();
	}

	public static void main(String[] args) throws IOException {

		Properties properties = new Properties();
		InputStream inputStream = new FileInputStream(configurationFile);
		properties.load(inputStream);

		fileNameHandleMap = new HashMap<String, Integer>();
		handleBlockIDMap = new HashMap<Integer, ArrayList<Integer>>();
		blockIDLocationMap = new HashMap<Integer, HashSet<DataNodeLocation>>();
		livingDataNodes = new HashMap<Integer, DataNodeLocation>();

		dataFile = new File(properties.getProperty("Data File"));
		commitTimeout = Integer.parseInt(properties.getProperty("Commit Timeout"));

		if ((dataFile == null) || (commitTimeout == null)) {
			System.out.println("Configuration Missing...");
			System.exit(-1);
		}

		loadData();

		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					try {
						commitData();
					} catch (IOException e) {
						e.printStackTrace();
					}
					try {
						Thread.sleep(commitTimeout);
					} catch (InterruptedException e) {
						// nope
					}
				}
			}
		}).start();

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					commitData();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}));

		System.out.println("Loaded NameNode");

		NameNode nameNode = new NameNode();
		Naming.rebind("NameNode", nameNode);
	}

	public NameNode() throws RemoteException {
		super();
	}

	@Override
	public byte[] assignBlock(byte[] serializedAssignBlockRequest) {
		return null;
	}

	@Override
	public byte[] blockReport(byte[] serializedBlockReportRequest) {
		try {
			BlockReportRequest blockReportRequest = BlockReportRequest.parseFrom(serializedBlockReportRequest);
			DataNodeLocation location = blockReportRequest.getLocation();
			Integer serverID = blockReportRequest.getId();
			if (livingDataNodes.get(serverID) == null) {
				livingDataNodes.put(serverID, location);
				System.out.println("New DataNode Registered... rmi://" + location.getIP() + ":" + location.getPort() + "/DataNode");
			}
			for (Integer tempBlockID : blockReportRequest.getBlockNumbersList()) {
				if (blockIDLocationMap.get(tempBlockID) == null) {
					blockIDLocationMap.put(tempBlockID, new HashSet<DataNodeLocation>());
				}
				blockIDLocationMap.get(tempBlockID).add(location);
			}
			BlockReportResponse.Builder blockReportResonse = BlockReportResponse.newBuilder();
			for (Integer temporaryIndex = 0; temporaryIndex < blockReportRequest.getBlockNumbersCount(); temporaryIndex++) {
				blockReportResonse.addStatus(1);
			}
			return blockReportResonse.build().toByteArray();
		} catch (InvalidProtocolBufferException e) {
			return BlockReportResponse.newBuilder().addStatus(0).build().toByteArray();
		}
	}

	@Override
	public byte[] closeFile(byte[] serializedCloseFileRequest) {
		try {
			CloseFileRequest closeFileRequest = CloseFileRequest.parseFrom(serializedCloseFileRequest);
			if (handleBlockIDMap.get(closeFileRequest.getHandle()) != null) {
				return CloseFileResponse.newBuilder().setStatus(1).build().toByteArray();
			} else {
				return CloseFileResponse.newBuilder().setStatus(0).build().toByteArray();
			}
		} catch (IOException e) {
			return CloseFileResponse.newBuilder().setStatus(0).build().toByteArray();
		}
	}

	@Override
	public byte[] getBlockLocations(byte[] serializedGetBlockLocationRequest) {
		return null;
	}

	@Override
	public byte[] heartBeat(byte[] serializedHeartBeatRequest) {
		try {
			HeartBeatRequest heartBeatRequest = HeartBeatRequest.parseFrom(serializedHeartBeatRequest);
			Integer serverID = heartBeatRequest.getId();
			if (livingDataNodes.get(serverID) != null) {
				System.out.println("DataNode : " + serverID.toString() + " beating...");
			} else {
				System.out.println("New DataNode Found... Awaiting BlockReport...");
			}
			return HeartBeatResponse.newBuilder().setStatus(1).build().toByteArray();
		} catch (InvalidProtocolBufferException e) {
			return HeartBeatResponse.newBuilder().setStatus(0).build().toByteArray();
		}
	}

	@Override
	public byte[] list(byte[] serializedListFilesRequest) {
		return ListFilesResponse.newBuilder().addAllFileNames(fileNameHandleMap.keySet()).setStatus(1).build().toByteArray();
	}

	@Override
	public byte[] openFile(byte[] serializedOpenFileRequest) {
		try {
			OpenFileRequest openFileRequest = OpenFileRequest.parseFrom(serializedOpenFileRequest);
			String fileName = openFileRequest.getFileName();
			Boolean forRead = openFileRequest.getForRead();
			if (forRead) {
				Integer handle = fileNameHandleMap.get(fileName);
				if (handle == null) {
					return OpenFileResponse.newBuilder().setStatus(0).build().toByteArray();
				} else {
					return OpenFileResponse.newBuilder().setHandle(handle).addAllBlockNums(handleBlockIDMap.get(handle)).build().toByteArray();
				}
			} else {
				Integer handle = getNewHandleID();
				fileNameHandleMap.put(fileName, handle);
				handleBlockIDMap.put(handle, new ArrayList<Integer>());
				return OpenFileResponse.newBuilder().setHandle(handle).build().toByteArray();
			}
		} catch (InvalidProtocolBufferException e) {
			return OpenFileResponse.newBuilder().setStatus(0).build().toByteArray();
		}
	}
}
