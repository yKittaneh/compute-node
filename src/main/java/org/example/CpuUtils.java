package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class CpuUtils {
    private static final Logger logger = Logger.getLogger(CpuUtils.class.getName());

    public static void main(String[] args) {
        logger.info("CpuUtils.main called");

        getTaskCpuLevel();
    }

    public static Double getTaskCpuLevel() {
        String taskCPU = null;

        String command = "top -b -n 1 -o +%CPU -c -w512 | sed -n '7,15p' | awk '{printf \"%9s === %-6s === %s ;\\n\",$1,$9,$0}'";

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("bash", "-c", command);
        pb.redirectError();

        StringBuilder sb = new StringBuilder();
        List<String> list = new ArrayList<>();
        char c;

        try {
            Process p = pb.start();
            InputStream is = p.getInputStream();
            int value;
            while ((value = is.read()) != -1) {
                c = ((char) value);
                if (c == '\n') {
                    list.add(sb.toString());
                    sb.setLength(0);
                    continue;
                }
                sb.append(c);
            }
            int exitCode = p.waitFor();
            logger.info("Top exited with " + exitCode);

            if (exitCode != 0) {
                sb.setLength(0);
                InputStream error = p.getErrorStream();
                while ((value = error.read()) != -1)
                    sb.append(((char) value));
                logger.warning("Error stream: " + sb);
            }

//            logger.info("Output:\n" + sb);
//            for (String ss : list){
//                logger.info(ss);
//            }

            p.destroyForcibly();

            for (String ss : list) {
                if (ss.contains("TaskSimulator")) {
                    taskCPU = ss.split("===")[1].trim();
                    logger.info("TaskSimulator cpu = " + taskCPU);
                    break;
                }
            }

        } catch (IOException | InterruptedException exp) {
            throw new RuntimeException(exp);
        }

        if (taskCPU != null)
            return Double.valueOf(taskCPU.replace(",", "."));
        else
            return null;
    }

}
