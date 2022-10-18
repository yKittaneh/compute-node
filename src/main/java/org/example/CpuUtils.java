package org.example;

import org.example.util.StreamGobbler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class CpuUtils {
    private static final Logger logger = Logger.getLogger(CpuUtils.class.getName());

    private static final String CONTAINER_NAME_PREFIX = "mosaik_container_";

    private static String CONTAINER_NAME;

    private static final String TASK_NAME = "TaskSimulator";

    public static List<String> CPU_LIMIT_PID_LIST = new ArrayList<>();

    public static String TASK_SIM_PID;

    private static double tempGridPowerSum;

    private static long tempGridPowerCount;

    public static void main(String[] args) {
        logger.info("CpuUtils.main called");

        getTaskCpuLevel();

//        getContainerName();
    }

    public static double getTaskCpuLevel() {
        logger.info("CpuUtils.getTaskCpuLevel called");

        if (isStringEmpty(CONTAINER_NAME))
            getContainerName();
//        Todo: how could computeNode know the name of the task to choose/grep it out of the result of docker top?! pass it as param from mosaik?
        String command = "docker top " + CONTAINER_NAME + " o pid,pcpu,cmd | awk '{ printf(\"%s === %s === %s\\n\", $2, $6, $7); }' | grep " + TASK_NAME;

        String resultLine = runCommandAndExtractLine(command, TASK_NAME);

        if (resultLine == null)
            throw new RuntimeException("Could not find task [" + TASK_NAME + "] in the result of command " + command + " - make sure the task is running and its name contains " + TASK_NAME);

        String[] splitResult = resultLine.split("===");

        logger.info("CPU level = " + splitResult[0].trim());
        return Double.parseDouble(splitResult[0].trim());
    }

    private static void getContainerName() {
        logger.info("CpuUtils.getContainerName called");
        String command = "docker ps --filter \"name=" + CONTAINER_NAME_PREFIX + "\" --format \"table {{.Names}}\"";

        CONTAINER_NAME = runCommandAndExtractLine(command, CONTAINER_NAME_PREFIX);

        if (CONTAINER_NAME == null)
            throw new RuntimeException("Could not find container in the result of command " + command + " - make sure the container is running and its name starts with " + CONTAINER_NAME_PREFIX);

        logger.info("CONTAINER_NAME = " + CONTAINER_NAME);
    }

    private static String runCommandAndExtractLine(String command, String matchingString) {
        String line;
        String resultLine = null;
        logger.info("going to run command " + command + ". Looking for [" + matchingString + "]");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("bash", "-c", command);
            Process process = builder.start();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains(matchingString)) {
                    resultLine = line;
                    logger.info("found it! ... " + CONTAINER_NAME);
                    break;
                }
            }

            process.waitFor();
            logger.info("exit: " + process.exitValue());
            process.destroy();

            return resultLine;
        } catch (Exception ex) {
            throw new RuntimeException("Error while getting container name", ex);
        }
    }

    private static void runCommand(String command) {
        logger.info("runCommand: " + command);

        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("bash", "-c", command);
            Process process = builder.start();
            StreamGobbler inputStreamGobbler = new StreamGobbler(process.getInputStream(), logger::info);
            Executors.newSingleThreadExecutor().submit(inputStreamGobbler);
            StreamGobbler errorStreamGobbler = new StreamGobbler(process.getErrorStream(), logger::warning);
            Executors.newSingleThreadExecutor().submit(errorStreamGobbler);
            int exitCode = process.waitFor();
            logger.info("exitCode: " + exitCode);

            process.destroy();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error while running command [" + command + "]", e);
        }
    }

    public static void updateCpuLimitProcessId() {
        logger.info("getting cpu limit process id");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("bash", "-c", "pidof cpulimit");
            Process process = builder.start();

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String commandResult = bufferedReader.readLine();
            logger.info("line: " + commandResult);
            process.waitFor();
            logger.info("exit: " + process.exitValue());
            process.destroy();

            logger.info("pidof cpulimit: " + commandResult);

            if (isStringEmpty(commandResult))
                logger.warning("blank result after running command 'pidof cpulimit'");
            else
                CPU_LIMIT_PID_LIST = new ArrayList<>(Arrays.asList(commandResult.split(" ")));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error while running command [pidof cpulimit]", e);
        }
    }

    public static void handleCpuLevel(double gridPower) {
        tempGridPowerCount++;
        tempGridPowerSum += gridPower;

        logger.info("+++ gridPower = [" + gridPower + "] - gridPowerAvg = [" + tempGridPowerSum / tempGridPowerCount + "]");

        if (isCpuLimitingNeeded(gridPower)) {
            // todo (high): consider taking the path of limiting cpu levels by a percentage relative to the gridPower value. Less power = Less CPU?

            logger.info("gridPower [" + gridPower + "] is less than half of the average, going to limit cpu");
            if (isCpuLimitProcessRunning()) {
                // there is already a cpuLimit process alive, no need to do anything. Unless... (see the todo above)
//                                killCpuLimitProcess();
                logger.info("there is already a running cpulimit process. Not doing anything");
            } else {
                startCpuLimitProcess();
                updateCpuLimitProcessId();
            }
        } else if (isCpuReleasingNeeded(gridPower)) {
            logger.info("gridPower [" + gridPower + "] is larger than the average, going to kill cpulimit process (if running)");
            if (isCpuLimitProcessRunning()) {
                // kill running cpulimit process, and clear CPU_LIMIT_PID_LIST
                killCpuLimitProcess();
            }
        }
    }

    public static void startCpuLimitProcess() {
        logger.info("startCpuLimitProcess called");
//        String command = "cpulimit -p " + getTaskSimPid() + " -l 50 -b";
//        runCommand(command);
        logger.info("startCpuLimitProcess finished");
    }

    public static void killCpuLimitProcess() {
        logger.info("called killCpuLimitProcess");
        runCommand("kill -9 " + String.join(" ", CPU_LIMIT_PID_LIST));

        // send SIGCONT signal to the task sim process in case cpulimit is killed while task sim is stopped (SIGSTOP) (as part of the cpulimit operation)
//        runCommand("kill -s SIGCONT " + getTaskSimPid());

        logger.info("clearing CPU_LIMIT_PID_LIST");
        CPU_LIMIT_PID_LIST.clear();
    }

    private static boolean isCpuLimitProcessRunning() {
        return CPU_LIMIT_PID_LIST.size() > 0;
    }

    private static boolean isCpuReleasingNeeded(double gridPower) {
        // todo: rewrite this part? relating to power model?
        return gridPower > (tempGridPowerSum / tempGridPowerCount) * 1.5;
    }

    private static boolean isCpuLimitingNeeded(double gridPower) {
        // todo: rewrite this part? relating to power model?
        return gridPower < (tempGridPowerSum / tempGridPowerCount) / 2;
    }

    public static boolean isStringEmpty(String string) {
        return string == null || string.isEmpty();
    }

}
