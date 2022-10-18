package org.example.util;

import org.example.ComputeNode;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.example.CpuUtils.isStringEmpty;
import static org.example.ComputeNode.DATE_TIME_FORMATTER;

public class ProfileFileReader {

    private final String fileName;
    private final String gridName;

    private String profileUnit;

    private Long profileNum;

    private Long profileResolution; //aka step size

    private LocalDateTime profileStartDateTime;

    private final Map<String, List<String>> profiles = new HashMap<>();

    private ArrayList<String> gridNodeIds = new ArrayList<>();

    public ProfileFileReader(String fileName, String gridName) {
        this.fileName = fileName;
        this.gridName = gridName;
    }

    public void read() {

        // todo/future improvements (low): validate the structure/format of the profiles file
        try (GZIPInputStream gis = new GZIPInputStream(ComputeNode.class.getClassLoader().getResourceAsStream(fileName))) {

            Reader decoder = new InputStreamReader(gis, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(decoder);

            boolean reachedProfiles = false;

            String[] splitLine;
            List<String> profilePowers;

            JSONParser parser = new JSONParser();
            for (String line; (line = bufferedReader.readLine()) != null; ) {
                if (isStringEmpty(line))
                    continue;

                if ("# meta".equals(line)) {
                    populateMetaData(bufferedReader, parser);
                    continue;
                }

                if ("# id_list".equals(line))
                    line = extractGridNodesIDs(bufferedReader, parser);

                if ("# profiles".equals(line)) {
                    reachedProfiles = true;
                    continue;
                }

                if (reachedProfiles) {
                    splitLine = line.split(",", 2);
                    profilePowers = Arrays.asList(splitLine[1].split(","));
                    profiles.put(splitLine[0], profilePowers);
                }
            }
        } catch (ParseException | IOException e) {
            throw new RuntimeException("Error while parsing profiles file", e);
        }
    }

    private void populateMetaData(BufferedReader bufferedReader, JSONParser parser) throws ParseException, IOException {
        JSONObject jsonObject = (JSONObject) parser.parse(bufferedReader.readLine());
        profileUnit = (String) jsonObject.get("unit");
        profileNum = (Long) jsonObject.get("num_profiles");
        profileResolution = (Long) jsonObject.get("resolution");
        profileStartDateTime = LocalDateTime.parse((CharSequence) jsonObject.get("start_date"), DATE_TIME_FORMATTER);
    }

    private String extractGridNodesIDs(BufferedReader bufferedReader, JSONParser parser) throws
            IOException, ParseException {
        StringBuilder stringBuilder = new StringBuilder();
        String line = bufferedReader.readLine();
        while (!line.contains("#")) {
            stringBuilder.append(line);
            line = bufferedReader.readLine();
        }

        JSONObject jsonObject = (JSONObject) parser.parse(stringBuilder.toString());
        this.gridNodeIds = (ArrayList<String>) jsonObject.get(this.gridName);
        return line;
    }

    public String getProfileUnit() {
        return profileUnit;
    }

    public Long getProfileNum() {
        return profileNum;
    }

    public Long getProfileResolution() {
        return profileResolution;
    }

    public LocalDateTime getProfileStartDateTime() {
        return profileStartDateTime;
    }

    public Map<String, List<String>> getProfiles() {
        return profiles;
    }

    public ArrayList<String> getGridNodeIds() {
        return gridNodeIds;
    }
}
