package chicago;

import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.router.TripStructureUtils;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class TripRecordCsvWriter {

    private BufferedWriter writer;
    private boolean headerWritten = false;

    public TripRecordCsvWriter(String outputFilePath) throws IOException {
        OutputStream fileStream = new FileOutputStream(outputFilePath);
        OutputStream gzipStream = new GZIPOutputStream(fileStream);
        writer = new BufferedWriter(new OutputStreamWriter(gzipStream, "UTF-8"));
    }

    public void writeTripRecord(Map<String, Object> tripRecord) throws IOException {
        if (!headerWritten) {
            writeHeader(tripRecord.keySet());
            headerWritten = true;
        }
        writeRow(tripRecord);
    }

    private void writeHeader(Set<String> keys) throws IOException {
        writer.write(String.join(",", keys));
        writer.newLine();
    }

    private void writeRow(Map<String, Object> record) throws IOException {
        List<String> values = new ArrayList<>();
        for (String key : record.keySet()) {
            Object value = record.get(key);
            values.add(value != null ? value.toString().replace(",", " ") : "");
        }
        writer.write(String.join(",", values));
        writer.newLine();
    }

    public void close() throws IOException {
        writer.close();
    }

    public static Map<String, Object> getTripRecordFromPlan(Plan plan) {
        Map<String, Object> tripRecord = new HashMap<>();
        for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {
            Coord fromCoord = trip.getOriginActivity().getCoord();
            Coord toCoord = trip.getDestinationActivity().getCoord();
            tripRecord.put("fromX", fromCoord.getX());
            tripRecord.put("fromY", fromCoord.getY());
            tripRecord.put("toX", toCoord.getX());
            tripRecord.put("toY", toCoord.getY());
            tripRecord.putAll(trip.getLegsOnly().getFirst().getAttributes().getAsMap());
        }
        return tripRecord;
    }

    public static void writeAllPlansToCsvGz(Collection<? extends Person> persons, String outputFilePath) throws IOException {
        TripRecordCsvWriter csvWriter = new TripRecordCsvWriter(outputFilePath);
        for (Person person : persons) {
            Plan plan = person.getSelectedPlan();
            Map<String, Object> tripRecord = getTripRecordFromPlan(plan);
            csvWriter.writeTripRecord(tripRecord);
        }
        csvWriter.close();
    }
}
