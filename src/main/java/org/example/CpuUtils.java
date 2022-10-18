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

public class CpuController {
    private static final Logger logger = Logger.getLogger(CpuController.class.getName());

    public static List<String> CPU_LIMIT_PID_LIST = new ArrayList<>();

    public static String TASK_SIM_PID;

    private static double tempGridPowerSum;
    private static long tempGridPowerCount;

    private static String getTaskSimPid() {
        if (TASK_SIM_PID == null) {
            String line;
            String taskSimProcessLine = null;

            try {
                ProcessBuilder builder = new ProcessBuilder();
                builder.command("bash", "-c", "ps f -A");
                Process process = builder.start();
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while ((line = bufferedReader.readLine()) != null) {
                    if (line.contains("org.example.TaskSimulator")) {
                        taskSimProcessLine = line;
                        logger.info("found it! ... " + taskSimProcessLine);
                        break;
                    }
                }

                process.waitFor();
                logger.info("exit: " + process.exitValue());
                process.destroy();

                updateTaskSimPID(taskSimProcessLine);
            } catch (Exception ex) {
                throw new RuntimeException("Error while getting task sim pid", ex);
            }
        }

        return TASK_SIM_PID;
    }

    private static void updateTaskSimPID(String taskSimProcessLine) {
        if (taskSimProcessLine == null)
            throw new RuntimeException("Could not find task sim process in the result of command 'ps f -A");

        String[] splitLine = taskSimProcessLine.trim().split(" ");
        TASK_SIM_PID = splitLine[0];

        logger.info("TASK_SIM_PID: " + TASK_SIM_PID);
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
        String command = "cpulimit -p " + getTaskSimPid() + " -l 50 -b";
        runCommand(command);
        logger.info("startCpuLimitProcess finished");
    }

    public static void killCpuLimitProcess() {
        logger.info("called killCpuLimitProcess");
        runCommand("kill -9 " + String.join(" ", CPU_LIMIT_PID_LIST));

        // send SIGCONT signal to the task sim process in case cpulimit is killed while task sim is stopped (SIGSTOP) (as part of the cpulimit operation)
        runCommand("kill -s SIGCONT " + getTaskSimPid());

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
