package org.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;

public class CpuUtils {
    private static final Logger logger = Logger.getLogger(CpuUtils.class.getName());

    private static final String CONTAINER_NAME_PREFIX = "mosaik_container_";

    private static String CONTAINER_NAME;

    private static final String TASK_NAME = "TaskSimulator";

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

    public static boolean isStringEmpty(String string) {
        return string == null || string.isEmpty();
    }

}
