package chicago.gpsdata;

import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;
import org.apache.parquet.hadoop.example.GroupWriteSupport;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeoParquetWriter {
    private static final int maxSegmentsPerFile = 10000;
    private static final CompressionCodecName compressionCodecName = CompressionCodecName.ZSTD;

    public static void writeGeoParquet(String outputPath, List<PseudoGpsEventHandler.TrajectorySegment> segments) throws Exception {
        MessageType schema = Types.buildMessage()
                .addField(Types.required(PrimitiveType.PrimitiveTypeName.BINARY).named("pseudoId"))
                .addField(Types.required(PrimitiveType.PrimitiveTypeName.DOUBLE).named("startTime"))
                .addField(Types.required(PrimitiveType.PrimitiveTypeName.DOUBLE).named("endTime"))
                .addField(Types.required(PrimitiveType.PrimitiveTypeName.BINARY).named("geometry"))
                .named("TrajectorySchema");


        Map<String, String> extraMeta = getStringStringMap();


        GroupWriteSupport.setSchema(schema, new org.apache.hadoop.conf.Configuration());


        int fileIndex = 0;
        int segmentCounter = 0;
        ParquetWriter<Group> writer = null;

        for (PseudoGpsEventHandler.TrajectorySegment segment : segments) {
            if (segmentCounter % maxSegmentsPerFile == 0) {
                if (writer != null) writer.close();
                String chunkedPath = String.format(outputPath.replace(".parquet", "_%03d.parquet"), fileIndex++);
                writer = ExampleParquetWriter.builder(new org.apache.hadoop.fs.Path(chunkedPath))
                        .withWriteMode(org.apache.parquet.hadoop.ParquetFileWriter.Mode.OVERWRITE)
                        .withCompressionCodec(compressionCodecName)
                        .withType(schema)
                        .withExtraMetaData(extraMeta)
                        .build();
            }

            Group group = new SimpleGroup(schema);
            group.add("pseudoId", segment.pseudoId);
            group.add("startTime", segment.startTime);
            group.add("endTime", segment.endTime);
            group.add("geometry", segment.toWKT());
            writer.write(group);

            segmentCounter++;
        }
        if (writer != null) writer.close();

    }

    private static Map<String, String> getStringStringMap() {
        String geoMetadataJson = """
                {
                  "version": "0.4.0",
                  "primary_column": "geometry",
                  "columns": {
                    "geometry": {
                      "encoding": "WKT",
                      "geometry_type": "LineString",
                      "crs": "EPSG:4326"
                    }
                  }
                }
                """;

        Map<String, String> extraMeta = new HashMap<>();
        extraMeta.put("geo", geoMetadataJson);
        return extraMeta;
    }
}
