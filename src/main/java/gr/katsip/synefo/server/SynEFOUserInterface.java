package gr.katsip.synefo.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.StringTokenizer;

public class SynEFOUserInterface implements Runnable {

	private boolean _exit;

	private ZooMaster beastMaster;

	public SynEFOUserInterface(ZooMaster beastMaster) {
		this.beastMaster = beastMaster;
	}

	public void run() {
		_exit = false;
		BufferedReader _input = new BufferedReader(new InputStreamReader(System.in));
		String command = null;
		System.out.println("+efo Server started (UI). Type help for the list of commands");
		while(_exit == false) {
			try {
				System.out.print("+efo>");
				command = _input.readLine();
				if(command != null && command.length() > 0) {
					StringTokenizer strTok = new StringTokenizer(command, " ");
					String comm = strTok.nextToken();
					parseCommand(comm, strTok);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	public void parseCommand(String command, StringTokenizer strTok) {
		if(command.equals("scale-out")) {
			String compOne = null;
			if(strTok.hasMoreTokens()) {
				compOne = strTok.nextToken();
				if(beastMaster.physical_topology.containsKey(compOne) == false) {
					System.out.println("+efo error: Need to define an existing component-A when \"scale-out\" is issued.");
					return;
				}
			}else {
				System.out.println("+efo error: Need to define component-A (will {remove|add} task from/to downstream) when \"scale-out\" is issued.");
				return;
			}
			String compTwo = null;
			if(strTok.hasMoreTokens()) {
				compTwo = strTok.nextToken();
				if(beastMaster.physical_topology.containsKey(compTwo) == false) {
					System.out.println("+EFO error: Need to define an existing component-B when \"scale-out\" is issued.");
					return;
				}
			}else {
				System.out.println("+efo error: Need to define component-B (will be {remove|add}-ed from/to component-A downstream) when \"scale-out\" is issued.");
				return;
			}
			/**
			 * Issue the command for scaling out
			 */
			if(beastMaster.physical_topology.get(compOne).lastIndexOf(compTwo) == -1) {
				System.out.println("+efo error: " + compTwo + " is not an available downstream task of " + compOne + ".");
				return;
			}
			synchronized(beastMaster) {
				/**
				 * If the node is not active.
				 */
				if(beastMaster.active_topology.get(compOne).lastIndexOf(compTwo) < 0) {
					ArrayList<String> downstreamActiveTasks = beastMaster.active_topology.get(compOne);
					downstreamActiveTasks.add(compTwo);
					beastMaster.active_topology.put(compOne, downstreamActiveTasks);
				}
				String scaleOutCommand = "ADD~" + compTwo;
				beastMaster.setScaleCommand(compOne, scaleOutCommand);
			}
		}else if(command.equals("scale-in")) {
			String compOne = null;
			if(strTok.hasMoreTokens()) {
				compOne = strTok.nextToken();
				if(beastMaster.physical_topology.containsKey(compOne) == false) {
					System.out.println("+efo error: Need to define an existing component-A when \"scale-out\" is issued.");
					return;
				}
			}else {
				System.out.println("+efo error: Need to define component-A (will {remove|add} task from/to downstream) when \"scale-out\" is issued.");
				return;
			}
			String compTwo = null;
			if(strTok.hasMoreTokens()) {
				compTwo = strTok.nextToken();
				if(beastMaster.physical_topology.containsKey(compTwo) == false) {
					System.out.println("+EFO error: Need to define an existing component-B when \"scale-out\" is issued.");
					return;
				}
			}else {
				System.out.println("+efo error: Need to define component-B (will be {remove|add}-ed from/to component-A downstream) when \"scale-out\" is issued.");
				return;
			}
			/**
			 * Issue the command for scaling out
			 */
			if(beastMaster.physical_topology.get(compOne).lastIndexOf(compTwo) == -1) {
				System.out.println("+efo error: " + compTwo + " is not an available downstream task of " + compOne + ".");
				return;
			}else if(beastMaster.active_topology.get(compOne).lastIndexOf(compTwo) == -1) {
				System.out.println("+efo error: " + compTwo + " is not an active downstream task of " + compOne + ".");
				return;
			}
			synchronized(beastMaster) {
				/**
				 * If the node is active.
				 */
				if(beastMaster.active_topology.get(compOne).lastIndexOf(compTwo) >= 0) {
					ArrayList<String> downstreamActiveTasks = beastMaster.active_topology.get(compOne);
					downstreamActiveTasks.remove(downstreamActiveTasks.lastIndexOf(compTwo));
					beastMaster.active_topology.put(compOne, downstreamActiveTasks);
				}
				String scaleInCommand = "REMOVE~" + compTwo;
				beastMaster.setScaleCommand(compOne, scaleInCommand);
			}
		}else if(command.equals("active-top")) {
			HashMap<String, ArrayList<String>> activeTopologyCopy = new HashMap<String, ArrayList<String>>(beastMaster.active_topology);
			Iterator<Entry<String, ArrayList<String>>> itr = activeTopologyCopy.entrySet().iterator();
			while(itr.hasNext()) {
				Entry<String, ArrayList<String>> entry = itr.next();
				String task = entry.getKey();
				ArrayList<String> downStream = entry.getValue();
				System.out.println("\tTask: " + task + " down stream: ");
				for(String t : downStream) {
					System.out.println("\t\t" + t);
				}
			}
		}else if(command.equals("physical-top")) {
			HashMap<String, ArrayList<String>> physicalTopologyCopy = new HashMap<String, ArrayList<String>>(beastMaster.physical_topology);
			Iterator<Entry<String, ArrayList<String>>> itr = physicalTopologyCopy.entrySet().iterator();
			while(itr.hasNext()) {
				Entry<String, ArrayList<String>> entry = itr.next();
				String task = entry.getKey();
				ArrayList<String> downStream = entry.getValue();
				System.out.println("\tTask: " + task + " down stream: ");
				for(String t : downStream) {
					System.out.println("\t\t" + t);
				}
			}
		}else if(command.equals("help")) {
			/**
			 * Print help instructions
			 */
			System.out.println("Available commands:");
			System.out.println("\t scale-out <component-one> <component-two>: action:{add,remove}");
			System.out.println("\t\t component-one: task that will have downstream modified");
			System.out.println("\t\t component-two: task that will either be added");
			System.out.println("\t scale-in <component-one> <component-two>: action:{add,remove}");
			System.out.println("\t\t component-one: task that will have downstream modified");
			System.out.println("\t\t component-two: task that will be removed");
			System.out.println("\t active-top: Prints out the current working topology");
			System.out.println("\t physical-top: Prints out the physical topology");
			System.out.println("\t quit: self-explanatory");
		}else if(command.equals("quit")) {
			_exit = true;
		}else {
			System.out.println("Unrecognized command. Type help to see list of commands");
		}
	}

}
