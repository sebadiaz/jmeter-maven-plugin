package com.lazerycode.jmeter.testrunner;

import com.lazerycode.jmeter.JMeterMojo;
import com.lazerycode.jmeter.UtilityFunctions;
import com.lazerycode.jmeter.configuration.JMeterArgumentsArray;
import com.lazerycode.jmeter.configuration.JMeterProcessJVMSettings;
import com.lazerycode.jmeter.configuration.RemoteArgumentsArrayBuilder;
import com.lazerycode.jmeter.configuration.RemoteConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.tools.ant.DirectoryScanner;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TestManager encapsulates functions that gather JMeter Test files and execute the tests
 */
public class TestManager extends JMeterMojo {

	private final JMeterArgumentsArray baseTestArgs;
	private final File binDir;
	private final File testFilesDirectory;
	private final List<String> testFilesIncluded;
	private final List<String> testFilesExcluded;
	private final boolean suppressJMeterOutput;
	private final RemoteConfiguration remoteServerConfiguration;
	private final JMeterProcessJVMSettings jMeterProcessJVMSettings;
	private final PrePostProcessingConfig prePostProcessingConfig;


	public TestManager(JMeterArgumentsArray baseTestArgs, File testFilesDirectory, List<String> testFilesIncluded, List<String> testFilesExcluded, RemoteConfiguration remoteServerConfiguration, boolean suppressJMeterOutput, File binDir, JMeterProcessJVMSettings jMeterProcessJVMSettings, PrePostProcessingConfig prePostProcessingConfig) {
		this.binDir = binDir;
		this.baseTestArgs = baseTestArgs;
		this.testFilesDirectory = testFilesDirectory;
		this.testFilesIncluded = testFilesIncluded;
		this.testFilesExcluded = testFilesExcluded;
		this.remoteServerConfiguration = remoteServerConfiguration;
		this.suppressJMeterOutput = suppressJMeterOutput;
		this.jMeterProcessJVMSettings = jMeterProcessJVMSettings;
		this.prePostProcessingConfig=prePostProcessingConfig;
	}




	/**
	 * Executes all tests and returns the resultFile names
	 *
	 * @return the list of resultFile names
	 * @throws MojoExecutionException
	 */
	public List<String> executeTests() throws MojoExecutionException {
		runScript(prePostProcessingConfig.getGlobalPreProcessingScript(), prePostProcessingConfig.getDirectory());

		JMeterArgumentsArray thisTestArgs = baseTestArgs;
		List<String> tests = generateTestList();
		List<String> results = new ArrayList<String>();
		for (String file : tests) {
			if (remoteServerConfiguration != null) {
				if ((remoteServerConfiguration.isStartServersBeforeTests() && tests.get(0).equals(file)) || remoteServerConfiguration.isStartAndStopServersForEachTest()) {
					thisTestArgs.setRemoteStart();
					thisTestArgs.setRemoteStartServerList(remoteServerConfiguration.getServerList());
				}
				if ((remoteServerConfiguration.isStopServersAfterTests() && tests.get(tests.size() - 1).equals(file)) || remoteServerConfiguration.isStartAndStopServersForEachTest()) {
					thisTestArgs.setRemoteStop();
				}
			}
			results.add(executeSingleTest(new File(testFilesDirectory, file), thisTestArgs));
		}
		runScript(prePostProcessingConfig.getGlobalPostProcessingScript(), prePostProcessingConfig.getDirectory());
		return results;
	}

	//=============================================================================================

	/**
	 * Executes a single JMeter test by building up a list of command line
	 * parameters to pass to JMeter.start().
	 *
	 * @param test JMeter test XML
	 * @return the report file names.
	 * @throws org.apache.maven.plugin.MojoExecutionException Exception
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private String executeSingleTest(File test, JMeterArgumentsArray testArgs) throws MojoExecutionException {
		getLog().info(" ");
		testArgs.setTestFile(test);
		//Delete results file if it already exists
		new File(testArgs.getResultsLogFileName()).delete();
		List<String> argumentsArray = testArgs.buildArgumentsArray();
		argumentsArray.addAll(buildRemoteArgs(remoteServerConfiguration));
		getLog().debug("JMeter is called with the following command line arguments: " + UtilityFunctions.humanReadableCommandLineOutput(argumentsArray));
		getLog().info("Executing test: " + test.getName());
		//Start the test.
		JMeterProcessBuilder JMeterProcessBuilder = new JMeterProcessBuilder(jMeterProcessJVMSettings);
		JMeterProcessBuilder.setWorkingDirectory(binDir);
		JMeterProcessBuilder.addArguments(argumentsArray);

		try {
			StringBuilder commandStr=new StringBuilder();
			commandStr.append(prePostProcessingConfig.getPreProcessingScript());
			if(prePostProcessingConfig.isAddTestNameOnScript()){
				commandStr.append(" ").append(test.getName().toString());
			}

			runScript(commandStr.toString(), prePostProcessingConfig.getDirectory());

			final Process process = JMeterProcessBuilder.startProcess();
			BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = br.readLine()) != null) {
				if (suppressJMeterOutput) {
					getLog().debug(line);
				} else {
					getLog().info(line);
				}
			}
			int jMeterExitCode = process.waitFor();
			if (jMeterExitCode != 0) {
				throw new MojoExecutionException("Test failed");
			}
			getLog().info("Completed Test: " + test.getName());
		} catch (InterruptedException ex) {
			getLog().info(" ");
			getLog().info("System Exit Detected!  Stopping Test...");
			getLog().info(" ");
		} catch (IOException e) {
			getLog().error(e.getMessage());
		}finally {
			StringBuilder commandStr=new StringBuilder();
			commandStr.append(prePostProcessingConfig.getPostProcessingScript());
			if(prePostProcessingConfig.isAddTestNameOnScript()){
				commandStr.append(" ").append(test.getName().toString());
			}
			runScript(commandStr.toString(), prePostProcessingConfig.getDirectory());

		}
		return testArgs.getResultsLogFileName();
	}

	private List<String> buildRemoteArgs(RemoteConfiguration remoteConfig) {
		if (remoteConfig == null) {
			return Collections.emptyList();
		}
		return new RemoteArgumentsArrayBuilder().buildRemoteArgumentsArray(remoteConfig.getMasterPropertiesMap());
	}

	/**
	 * Scan Project directories for JMeter Test Files according to includes and excludes
	 *
	 * @return found JMeter tests
	 */
	private List<String> generateTestList() {
		List<String> jmeterTestFiles = new ArrayList<String>();
		DirectoryScanner scanner = new DirectoryScanner();
		scanner.setBasedir(this.testFilesDirectory);
		scanner.setIncludes(this.testFilesIncluded == null ? new String[]{"**/*.jmx"} : this.testFilesIncluded.toArray(new String[jmeterTestFiles.size()]));
		if (this.testFilesExcluded != null) {
			scanner.setExcludes(this.testFilesExcluded.toArray(new String[testFilesExcluded.size()]));
		}
		scanner.scan();
		final List<String> includedFiles = Arrays.asList(scanner.getIncludedFiles());
		jmeterTestFiles.addAll(includedFiles);
		return jmeterTestFiles;
	}


	private void runScript(String command, File dir)  {
		getLog().debug("Call script : " + command);
		try {
			if(command!=null||!command.isEmpty()) {
				Process process= Runtime.getRuntime().exec(command.split(" "),null,dir);
				BufferedReader reader =null;
				try{
					reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					String line = "";
					while ((line = reader.readLine()) != null) {
						getLog().info("Script output:" + line);
					}
				}finally {
					if(reader!=null){
						reader.close();
					}
				}
			}
		} catch (IOException e) {
			getLog().error(e.getMessage());
		}

	}
}