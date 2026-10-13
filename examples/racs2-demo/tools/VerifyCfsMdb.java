import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.yamcs.ProcessorConfig;
import org.yamcs.YConfiguration;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.mdb.MetaCommandProcessor;
import org.yamcs.mdb.ProcessorData;
import org.yamcs.mdb.XtceTmExtractor;
import org.yamcs.utils.TimeEncoding;

/** Compare XTCE command encoding and telemetry decoding to real upstream C structs. */
public class VerifyCfsMdb {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: VerifyCfsMdb MDB_XML C_FIXTURES_JSON");
        }
        TimeEncoding.setUp();
        YConfiguration.setupTest(null);
        var modules = List.of("cfe_es", "cfe_evs", "cfe_sb", "cfe_tbl", "cfe_time", "to_lab", "ci_lab");
        var children = new ArrayList<Map<String, Object>>();
        var directory = Path.of(args[0]).toAbsolutePath().getParent();
        for (var cpu : List.of("cpu1", "cpu2")) {
            var apps = new ArrayList<Map<String, Object>>();
            for (var module : modules) {
                apps.add(Map.of("type", "xtce", "args",
                        Map.of("file", directory.resolve(cpu + "/" + module + ".xml").toString())));
            }
            children.add(Map.of("type", "xtce", "args", Map.of("file", directory.resolve(cpu + ".xml").toString()),
                    "subLoaders", apps));
        }
        var config = YConfiguration.wrap(Map.of("type", "xtce", "args", Map.of("file", args[0]),
                "subLoaders", children));
        var mdb = MdbFactory.createInstance(List.of(config), false, false);
        var processor = new MetaCommandProcessor(new ProcessorData("verify-cfs", mdb, new ProcessorConfig()));
        var extractor = new XtceTmExtractor(mdb);
        extractor.provideAll();
        int commands = 0;
        int telemetry = 0;
        var fixtures = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        for (var item : fixtures) {
            var fixture = item.getAsJsonObject();
            var name = fixture.get("name").getAsString();
            var module = modules.stream().map(String::toUpperCase)
                    .filter(m -> name.startsWith(m + "_")).findFirst().orElseThrow();
            var prefix = "/cFS/" + fixture.get("cpu").getAsString() + "/" + module + "/";
            byte[] expected = HexFormat.of().parseHex(fixture.get("hex").getAsString());
            var values = fixture.getAsJsonObject("values");
            if (fixture.get("kind").getAsString().equals("command")) {
                var assignment = new HashMap<String, Object>();
                for (var entry : values.entrySet()) {
                    assignment.put(entry.getKey(), entry.getValue().getAsString());
                }
                var command = mdb.getMetaCommand(prefix + name);
                byte[] actual = processor.buildCommand(command, assignment, 0).getCmdPacket();
                if (!Arrays.equals(expected, actual)) {
                    throw new AssertionError(name + " C bytes=" + HexFormat.of().formatHex(expected)
                            + " XTCE bytes=" + HexFormat.of().formatHex(actual));
                }
                commands++;
            } else {
                var result = extractor.processPacket(expected, 0, 0, 0);
                if (result.getContainerResult().stream()
                        .noneMatch(c -> c.getContainer().getQualifiedName().equals(prefix + name))) {
                    throw new AssertionError("Wrong telemetry container for " + name);
                }
                for (var entry : values.entrySet()) {
                    var parameter = mdb.getParameter(prefix + name + "_" + entry.getKey());
                    var pv = result.getParameterResult().getFirstInserted(parameter);
                    if (pv == null) {
                        throw new AssertionError("Missing parameter " + parameter.getName());
                    }
                    var wanted = entry.getValue().getAsJsonPrimitive();
                    var actual = pv.getEngValue();
                    if (wanted.isString()) {
                        if (!wanted.getAsString().equals(actual.getStringValue())) {
                            throw new AssertionError(parameter.getName() + ": " + actual);
                        }
                    } else if (Double.parseDouble(actual.toString()) != wanted.getAsDouble()) {
                        throw new AssertionError(parameter.getName() + ": " + actual);
                    }
                }
                telemetry++;
            }
        }
        if (commands != mdb.getMetaCommands().size() || telemetry + 1 != mdb.getSequenceContainers().size()) {
            throw new AssertionError("Fixture coverage does not match MDB contents");
        }
        System.out.printf("PASS: %d command packets match C byte for byte; %d telemetry packets decode correctly%n",
                commands, telemetry);
    }
}
