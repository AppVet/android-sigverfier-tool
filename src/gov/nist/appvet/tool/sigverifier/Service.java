/* This software was developed by employees of the National Institute of
 * Standards and Technology (NIST), an agency of the Federal Government.
 * Pursuant to title 15 United States Code Section 105, works of NIST
 * employees are not subject to copyright protection in the United States
 * and are considered to be in the public domain.  As a result, a formal
 * license is not needed to use the software.
 * 
 * This software is provided by NIST as a service and is expressly
 * provided "AS IS".  NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED
 * OR STATUTORY, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT
 * AND DATA ACCURACY.  NIST does not warrant or make any representations
 * regarding the use of the software or the results thereof including, but
 * not limited to, the correctness, accuracy, reliability or usefulness of
 * the software.
 * 
 * Permission to use this software is contingent upon your acceptance
 * of the terms of this agreement.
 */
package gov.nist.appvet.tool.sigverifier;

import gov.nist.appvet.tool.sigverifier.util.FileUtil;
import gov.nist.appvet.tool.sigverifier.util.HttpUtil;
import gov.nist.appvet.tool.sigverifier.util.Logger;
import gov.nist.appvet.tool.sigverifier.util.Protocol;
import gov.nist.appvet.tool.sigverifier.util.ReportFormat;
import gov.nist.appvet.tool.sigverifier.util.ReportUtil;
import gov.nist.appvet.tool.sigverifier.util.ToolStatus;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

/**
 * This class implements a synchronous tool service.
 */
public class Service extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final String reportName = "report";
	private static final Logger log = Properties.log;
	private static String appDirPath = null;
	private String appFilePath = null;
	private String reportFilePath = null;
	private String fileName = null;
	private String appId = null;
	private String command = null;
	private StringBuffer reportBuffer = null;

	/** CHANGE (START): Add expected HTTP request parameters **/
	/** CHANGE (END): Add expected HTTP request parameters **/
	public Service() {
		super();
	}

	/*
	 * // AppVet tool services will rarely use HTTP GET protected void
	 * doGet(HttpServletRequest request, HttpServletResponse response) throws
	 * ServletException, IOException {
	 */

	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		// Get received HTTP parameters and file upload
		FileItemFactory factory = new DiskFileItemFactory();
		ServletFileUpload upload = new ServletFileUpload(factory);
		List items = null;
		FileItem fileItem = null;

		try {
			items = upload.parseRequest(request);
		} catch (FileUploadException e) {
			e.printStackTrace();
		}

		// Get received items
		Iterator iter = items.iterator();
		FileItem item = null;

		while (iter.hasNext()) {
			item = (FileItem) iter.next();
			if (item.isFormField()) {
				// Get HTML form parameters
				String incomingParameter = item.getFieldName();
				String incomingValue = item.getString();
				if (incomingParameter.equals("appid")) {
					appId = incomingValue;
				}
				/** CHANGE (START): Get other tools-specific form parameters **/
				/** CHANGE (END): Get other tools-specific form parameters **/
			} else {
				// item should now hold the received file
				if (item != null) {
					fileItem = item;
					log.debug("Received file: " + fileItem.getName());
				}
			}
		}

		if (appId == null) {
			// All tool services require an AppVet app ID
			HttpUtil.sendHttp400(response, "No app ID specified");
			return;
		}

		if (fileItem != null) {
			// Get app file
			fileName = FileUtil.getFileName(fileItem.getName());
			if (!fileName.endsWith(".apk")) {
				HttpUtil.sendHttp400(response,
						"Invalid app file: " + fileItem.getName());
				return;
			}
			// Create app directory
			appDirPath = Properties.TEMP_DIR + "/" + appId;
			File appDir = new File(appDirPath);
			if (!appDir.exists()) {
				appDir.mkdir();
			}
			// Create report path
			reportFilePath = Properties.TEMP_DIR + "/" + appId + "/"
					+ reportName + "." + Properties.reportFormat.toLowerCase();

			appFilePath = Properties.TEMP_DIR + "/" + appId + "/" + fileName;
			log.debug("App file path: " + appFilePath);
			if (!FileUtil.saveFileUpload(fileItem, appFilePath)) {
				HttpUtil.sendHttp500(response, "Could not save uploaded file");
				return;
			}
		} else {
			HttpUtil.sendHttp400(response, "No app was received.");
			return;
		}

		// Use if reading command from ToolProperties.xml. Otherwise,
		// comment-out if using custom command (called by customExecute())
		command = getCommand();

		// If asynchronous, send acknowledgement back to AppVet now
		if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			HttpUtil.sendHttp202(response, "Received app " + appId
					+ " for processing.");
		}
		/*
		 * CHANGE: Select either execute() to execute a native OS command or
		 * customExecute() to execute your own custom code. Make sure that the
		 * unused method call is commented-out.
		 */
		reportBuffer = new StringBuffer();
		boolean succeeded = execute(command, reportBuffer);
		// boolean succeeded = customExecute(reportBuffer);
		if (!succeeded) {
			log.error("Error detected: " + reportBuffer.toString());
			String errorReport = ReportUtil
					.getHtmlReport(
							response,
							fileName,
							ToolStatus.ERROR,
							reportBuffer.toString(),
							"Description: \tApp is signed.\n\n",
							"Description: \tApp is unsigned or incorrectly signed.\n\n",
							null,
							"Description: \tError or exception processing app.\n\n");
			// Send report to AppVet
			if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
				// Send back ASCII in HTTP Response
				ReportUtil.sendInHttpResponse(response, errorReport,
						ToolStatus.ERROR);
			} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
				// Send report file in new HTTP Request to AppVet
				if (FileUtil.saveReport(errorReport, reportFilePath)) {
					ReportUtil.sendInNewHttpRequest(appId, reportFilePath,
							ToolStatus.ERROR);
				}
			}
			return;
		}

		// Analyze report and generate tool status
		log.debug("Analyzing report for " + appFilePath);
		ToolStatus reportStatus = ReportUtil.analyzeReport(reportBuffer
				.toString());
		log.debug("Result: " + reportStatus.name());
		String reportContent = null;

		// Get report
		if (Properties.reportFormat.equals(ReportFormat.HTML.name())) {
			reportContent = ReportUtil
					.getHtmlReport(
							response,
							fileName,
							reportStatus,
							reportBuffer.toString(),
							"Description: \tApp is signed (Note: some warnings may exist. See below for details).\n\n",
							"Description: \tApp is unsigned or incorrectly signed.\n\n",
							"Description: \tApp is unsigned or incorrectly signed.\n\n",
							"Description: \tError or exception processing app.\n\n");
		} else if (Properties.reportFormat.equals(ReportFormat.TXT.name())) {
			reportContent = getTxtReport();
		} else if (Properties.reportFormat.equals(ReportFormat.PDF.name())) {
			reportContent = getPdfReport();
		} else if (Properties.reportFormat.equals(ReportFormat.JSON.name())) {
			reportContent = getJsonReport();
		}

		// If report is null or empty, stop processing
		if (reportContent == null || reportContent.isEmpty()) {
			log.error("Tool report is null or empty");
			return;
		}

		// Send report to AppVet
		if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
			// Send back ASCII in HTTP Response
			ReportUtil
			.sendInHttpResponse(response, reportContent, reportStatus);
		} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			// Send report file in new HTTP Request to AppVet
			if (FileUtil.saveReport(reportContent, reportFilePath)) {
				ReportUtil.sendInNewHttpRequest(appId, reportFilePath,
						reportStatus);
			}
		}

		// Clean up
		if (!Properties.keepApps) {
			if (FileUtil.deleteDirectory(new File(appDirPath))) {
				log.debug("Deleted " + appFilePath);
			} else {
				log.warn("Could not delete " + appFilePath);
			}
		}

		reportBuffer = null;
		
		// Save logs if necessary
		saveLog();
		
		// Clean up
		System.gc();
	}

	public String getCommand() {
		// Get command from ToolProperties.xml file
		String cmd1 = Properties.command;
		String cmd2 = null;
		if (cmd1.indexOf(Properties.APP_FILE_PATH) > -1) {
			// Add app file path
			cmd2 = cmd1.replace(Properties.APP_FILE_PATH, appFilePath);
		} else {
			cmd2 = cmd1;
		}

		log.debug("full command: " + cmd2);
		return cmd2;
	}

	private static boolean execute(String command, StringBuffer output) {
		List<String> commandArgs = Arrays.asList(command.split("\\s+"));
		ProcessBuilder pb = new ProcessBuilder(commandArgs);
		Process process = null;
		IOThreadHandler outputHandler = null;
		IOThreadHandler errorHandler = null;
		int exitValue = -1;
		try {
			if (command == null || command.isEmpty()) {
				log.error("Command is null or empty");
				return false;
			}
			log.debug("Executing " + command);
			process = pb.start();
			outputHandler = new IOThreadHandler(process.getInputStream());
			outputHandler.start();
			errorHandler = new IOThreadHandler(process.getErrorStream());
			errorHandler.start();
			if (process.waitFor(Properties.commandTimeout,
					TimeUnit.MILLISECONDS)) {
				// Process has waited and exited within the timeout
				exitValue = process.exitValue();
				if (exitValue == 0) {
					log.debug("Command terminated normally: \n"
							+ outputHandler.getOutput() + "\nErrors: "
							+ errorHandler.getOutput());
					StringBuffer resultOut = outputHandler.getOutput();
					output.append(resultOut);
					return true;
				} else {
					log.error("Command terminated abnormally: \n"
							+ outputHandler.getOutput() + "\nErrors: "
							+ errorHandler.getOutput());
					StringBuffer resultError = errorHandler.getOutput();
					output.append(resultError);
					return false;
				}
			} else {
				// Process exceed timeout or was interrupted
				log.error("Command timed-out or was interrupted: \n"
						+ outputHandler.getOutput() + "\nErrors: "
						+ errorHandler.getOutput());
				StringBuffer resultOutput = outputHandler.getOutput();
				StringBuffer resultError = errorHandler.getOutput();
				if (resultOutput != null) {
					output.append(resultOutput);
					return false;
				} else if (resultError != null) {
					output.append(resultError);
				} else {
					output.append(Properties.toolName + " timed-out");
				}
				return false;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} catch (InterruptedException e) {
			e.printStackTrace();
			return false;
		} finally {
			if (outputHandler.isAlive()) {
				try {
					outputHandler.inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (errorHandler.isAlive()) {
				try {
					errorHandler.inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (process.isAlive()) {
				process.destroy();
			}
		}
	}

	private static class IOThreadHandler extends Thread {
		private InputStream inputStream;
		private StringBuffer output = new StringBuffer();
		private static final String lineSeparator = System
				.getProperty("line.separator");;

				IOThreadHandler(InputStream inputStream) {
					this.inputStream = inputStream;
				}

				public void run() {
					Scanner br = null;

					br = new Scanner(new InputStreamReader(inputStream));
					String line = null;
					while (br.hasNextLine()) {
						line = br.nextLine();
						output.append(line + lineSeparator);
					}
					br.close();
				}

				public StringBuffer getOutput() {
					return output;
				}
	}
	
	public static synchronized void saveLog() {
		// Check if log has been saved per save frequency
		boolean logExists = false;
		
		// Get current month
		java.util.Date date = new Date();
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		int currentMonth = cal.get(Calendar.MONTH) + 1; // cal.get() is zero-based
		int currentDay = cal.get(Calendar.DAY_OF_MONTH);
		int currentYear = cal.get(Calendar.YEAR);
		String currentDayStr = null;
		if (currentDay >= 1 && currentDay <= 9) {
			currentDayStr = "0" + currentDay;
		} else {
			currentDayStr = currentDay + "";
		}
		String currentMonthStr = null;
		if (currentMonth >= 1 && currentMonth <= 9) {
			currentMonthStr = "0" + currentMonth;
		} else {
			currentMonthStr = currentMonth + "";
		}
		String currentYearStr = currentYear + "";

		//log.debug("CURRENT MONTH: " + currentMonthStr);
		//log.debug("CURRENT DAY: " + currentDayStr);
		//log.debug("CURRENT YEAR: " + currentYearStr);

		// Scan log files in /logs directory. The name format for log files is
		// MM-DD-YYYY_appvet_log.txt
		String kryptowireLogsPath = Properties.ANDROID_SIGVERIFIER_FILES_HOME + "/logs";

		File folder = new File(kryptowireLogsPath);
		if (!folder.exists()) {
			log.error("Kryptowire logs directory does not exist");
			return;
		}
		File[] listOfFiles = folder.listFiles();
		for (int i = 0; i < listOfFiles.length; i++) {
			if (listOfFiles[i].isFile()) {
				//log.debug("File " + listOfFiles[i].getName());
				String logName = listOfFiles[i].getName();
				String logMonth = logName.substring(0, 2);
				String logYear = logName.substring(6, 10);
				//log.debug("logMonth: " + logMonth);
				//log.debug("logYear: " + logYear);
				
				if (currentMonthStr.equals(logMonth) && currentYearStr.equals(logYear)) {
					// Log already exists for current month so break
					logExists = true;
					break;
				}

			} else if (listOfFiles[i].isDirectory()) {
				//log.debug("Directory " + listOfFiles[i].getName());
			}
		}
		
		if (logExists) {
			return;
		} else {
			// Log does not exist for current month so copy appvet_log.txt to 
			// new log file 'MM-DD-YYYY_appvet_log.txt and CLEAR appvet_log.txt
			String destinationPath = kryptowireLogsPath + "/" + currentMonthStr + "-" + currentDayStr + "-" + currentYearStr + 
					"_appvet_log.txt";
			try {
				// Copy active log file to saved log file
				log.info("Saving log file: " + destinationPath);
				Files.copy(Paths.get(kryptowireLogsPath), new FileOutputStream(destinationPath));
				// Clear active log file
				log.info("Clearing active log");
				PrintWriter pw = new PrintWriter(kryptowireLogsPath);
				pw.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	// TODO
	public String getTxtReport() {
		return null;
	}

	// TODO
	public String getPdfReport() {
		return null;
	}

	// TODO
	public String getJsonReport() {
		return null;
	}
}
