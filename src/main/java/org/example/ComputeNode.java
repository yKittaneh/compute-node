package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.offis.mosaik.api.SimProcess;
import de.offis.mosaik.api.Simulator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static java.lang.Math.abs;

public class ComputeNode extends Simulator {
    private static final Logger logger = Logger.getLogger(ComputeNode.class.getName());

    private static final JSONObject META = (JSONObject) JSONValue.parse(("{"
            + "    'api_version': " + Simulator.API_VERSION + ","
            + "    'type': 'time-based',"
            + "    'models': {"
            + "        'ComputeNode': {"
            + "            'public': true,"
            + "            'params': [],"
            + "            'attrs': ['battery_power', 'pv_power', 'container_need', 'cpu_level']"
            + "        }"
            + "    }"
            + "}").replace("'", "\""));

    private static long STEP_SIZE;

    final Map<Long, Map<String, Object>> stepsInfo;

    private Long stepCount = 0L;

    public static DateTimeFormatter DATE_TIME_FORMATTER;

    private static String ENTITY_ID;

    private static Float TIME_RESOLUTION;

    private double pvPower;
    private double batteryPower;
    private double containerNeed;

    private double cpuLevel;

    private static RestTemplate restTemplate;

    private static String REST_SERVICE_URL;

    private static double MIN_CONSUMPTION;

    private static double MAX_CONSUMPTION;

    private static Boolean isRestServerReachable = null;

    public ComputeNode(String simName, String restUrl) {
        super(simName);

        LogManager.getLogManager().addLogger(logger);
        logger.setLevel(Level.FINE);

        this.stepsInfo = new HashMap<>();
        DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        REST_SERVICE_URL = restUrl;
        ClientHttpRequestFactory factory = new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory());
        restTemplate = new RestTemplate(factory);

        logger.info("org.example.ControlNode constructed");
    }

    public static void main(String[] args) throws Exception {
        logger.info("org.example.ControlNode main started ... ");

//        testRestServer();

//        testCpuLimit();

        runSimulation(args);

        logger.info("org.example.ControlNode main finished");
    }

    private static void runSimulation(String[] args) throws Exception {
        Simulator sim = new ComputeNode("ControlNode", "http://localhost:5567");
        if (args.length < 1) {
            String[] ipaddr = {"127.0.0.1:8000"};
            SimProcess.startSimulation(ipaddr, sim);
        } else {
            logger.info("args: " + Arrays.toString(args));
            SimProcess.startSimulation(args, sim);
        }
    }

    @Override
    public Map<String, Object> init(String sid, Float timeResolution, Map<String, Object> simParams) {
        ENTITY_ID = "computeNode";

        if (timeResolution != null)
            TIME_RESOLUTION = timeResolution;

        if (simParams.containsKey("step_size"))
            STEP_SIZE = (Long) simParams.get("step_size");

        if (simParams.containsKey("min_consumption"))
            MIN_CONSUMPTION = ((Number) simParams.get("min_consumption")).doubleValue();

        if (simParams.containsKey("max_consumption"))
            MAX_CONSUMPTION = ((Number) simParams.get("max_consumption")).doubleValue();

        return META;
    }

    @Override
    public List<Map<String, Object>> create(int num, String model, Map<String, Object> modelParams) throws Exception {
        if (num != 1)
            throw new RuntimeException("Value of param 'num' in create method = [" + num + "], expected 1. System design only allows for one entity per simulation.");

        JSONArray entities = new JSONArray();

        JSONObject entity = new JSONObject();
        entity.put("eid", ENTITY_ID);
        entity.put("type", model);
        entity.put("rel", new JSONArray());

        entities.add(entity);
        return entities;
    }

    @Override
    public long step(long time, Map<String, Object> inputs, long maxAdvance) {
        Map.Entry<String, Object> entity = (Map.Entry<String, Object>) inputs.entrySet().toArray()[0];
        Map<String, Object> attributes = (Map<String, Object>) entity.getValue();

        if (!attributes.containsKey("pv_power") || !attributes.containsKey("battery_power"))
            throw new RuntimeException("could not find pv_power and/or battery_power in the input map:\n " + inputs);

        handlePvPowerValue(((Number) ((JSONObject) attributes.get("pv_power")).values().toArray()[0]).doubleValue());
        handleBatteryPowerValue(((Number) ((JSONObject) attributes.get("battery_power")).values().toArray()[0]).doubleValue());

        // todo (low): is this needed? -- rn it saves each step's info but the info is not used. Use for evaluation?
        updateStepInfo();

        pushInfoToContainer();

        return time + STEP_SIZE;
    }

    @Override
    public Map<String, Object> getData(Map<String, List<String>> outputs) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> values = new HashMap<>();

        Map.Entry<String, List<String>> entity = (Map.Entry<String, List<String>>) outputs.entrySet().toArray()[0];
        if (!ENTITY_ID.equals(entity.getKey()))
            throw new RuntimeException("wrong entity id received [" + entity.getKey() + "], expected [" + ENTITY_ID + "].");

        Double cpu = CpuUtils.getTaskCpuLevel();
        for (String attr : entity.getValue()) {
            if ("container_need".equals(attr)) {
                this.containerNeed = applyPowerModel(cpu);
                values.put(attr, this.containerNeed);
                logger.info("container_need = " + this.containerNeed);
            }
            else if ("cpu_level".equals(attr)) {
                this.cpuLevel = cpu;
                values.put(attr, this.cpuLevel);
                logger.info("cpu_level = " + this.cpuLevel);
            } else {
                logger.warning("unexpected attr requested [" + attr + "]");
                throw new RuntimeException("unexpected attr requested [" + attr + "]");
            }
            data.put(ENTITY_ID, values);
        }

        return data;
    }

    private double applyPowerModel(double taskCpuLevel) {
        return MIN_CONSUMPTION + (taskCpuLevel / 100) * (MAX_CONSUMPTION - MIN_CONSUMPTION);
    }

    private void pushInfoToContainer() {
        if (isRestServerReachable()) {
            logger.info("pushing input map to container");

            logger.info("sending rest request to " + REST_SERVICE_URL + "/stepInformation");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ObjectMapper objectMapper = new ObjectMapper();

            Map<String, Double> infoMap = new HashMap<>();
            infoMap.put("pv_power", this.pvPower);
            infoMap.put("battery_power", this.batteryPower);

            try {
                String json = objectMapper.writeValueAsString(infoMap);
                HttpEntity<String> request = new HttpEntity<>(json, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(REST_SERVICE_URL + "/stepInformation", request, String.class);

                logger.info("response: " + response);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else
            logger.warning("rest server not reachable, skipping pushing info to container");
    }

    private static boolean isRestServerReachable() {
        if (isRestServerReachable == null) {
            logger.info("checking if rest server is reachable");
            try {
                URL url = new URL(REST_SERVICE_URL);
                HttpURLConnection huc = (HttpURLConnection) url.openConnection();

                int responseCode = huc.getResponseCode();

                logger.info("rest server responseCode = " + responseCode);

                isRestServerReachable = true;
            } catch (Exception ex) {
                logger.warning("failed to reach rest server with url: " + REST_SERVICE_URL);
                logger.severe("Exception: \n" + ex);
                logger.warning("continuing without pushing info to rest server");
                isRestServerReachable = false;
            }
        }
        return isRestServerReachable;
    }

    private void handlePvPowerValue(double pvPower) {
        this.pvPower = abs(pvPower);
        logger.info("+++ pvPower = [" + this.pvPower + "]");
    }

    private void handleBatteryPowerValue(double batteryPower) {
        this.batteryPower = abs(batteryPower);
        logger.info("+++ batterPower = [" + this.batteryPower + "]");
    }

    public void updateStepInfo() {
        this.stepsInfo.put(++this.stepCount, new HashMap<>());
        this.stepsInfo.get(this.stepCount).put("pvPower", this.pvPower);
        this.stepsInfo.get(this.stepCount).put("batteryPower", this.batteryPower);
        this.stepsInfo.get(this.stepCount).put("containerNeed", this.containerNeed);
    }

    private static void testRestServer() {
        logger.info("started testRestServer");

        ComputeNode node = new ComputeNode("testRestServer", "http://localhost:5567");

        logger.info("sending rest request to " + REST_SERVICE_URL + "/");
        ResponseEntity<String> response = restTemplate.getForEntity(REST_SERVICE_URL + "/", String.class);

        logger.info("response: " + response);

        node.pvPower = 987.6D;
        node.batteryPower = 2005.7D;

        node.pushInfoToContainer();

        logger.info("finished testRestServer");
    }
}
