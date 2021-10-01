/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.instrumentation.reporter;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;

import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.google.debugging.sourcemap.Base64VLQ;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.javascript.jscomp.instrumentation.reporter.proto.FileProfile;
import com.google.javascript.jscomp.instrumentation.reporter.proto.InstrumentationPoint;
import com.google.javascript.jscomp.instrumentation.reporter.proto.InstrumentationPointStats;
import com.google.javascript.jscomp.instrumentation.reporter.proto.ReportProfile;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class that helps to decode reports received from JS binaries that have been instrumented. Report
 * is usually a map of String => Long. Where key is encoded instrumentation point and value is
 * number of times that code was executed. To decode report beside report itself we need mapping
 * file that was produced when JS binary was compiled.
 *
 * <p>Expected usage: <code>{@code
 * Map<String, InstrumentationPoint> mapping = ReportDecoder.parseMappingFromFile("mapping.txt");
 * Map<String, Long> encodedReport = readFile("report.txt");
 * ReportProfile report = ReportDecoder.decodeReport(mapping, encodedReport);
 * }
 * </code>
 */
public final class ReportDecoder {

  private ReportDecoder() {}

  public static Map<String, InstrumentationPoint> parseMappingFromFile(String mappingFilePath)
      throws IOException {

    String fileContent =
        CharStreams.toString(Files.newBufferedReader(Paths.get(mappingFilePath), UTF_8));
    return ReportDecoder.parseMapping(fileContent);
  }

  /**
   * Parses the file found at location mappingFilePath and populates the properties of this class
   */
  public static Map<String, InstrumentationPoint> parseMapping(String mappingFileContent) {

    Map<String, InstrumentationPoint> mapping = new LinkedHashMap<>();

    List<String> linesOfFile = Splitter.on('\n').omitEmptyStrings().splitToList(mappingFileContent);

    // Report should contain at least three lines
    checkState(linesOfFile.size() >= 3, "Malformed report %s", linesOfFile);

    String fileNamesLine = linesOfFile.get(0).trim();
    String functionNamesLine = linesOfFile.get(1).trim();
    String typesLine = linesOfFile.get(2).trim();

    checkState(fileNamesLine.startsWith("FileNames:"));
    checkState(functionNamesLine.startsWith("FunctionNames:"));
    checkState(typesLine.startsWith("Types:"));

    String fileNamesAsJsonArray = fileNamesLine.substring(fileNamesLine.indexOf(":") + 1);
    String functionNamesAsJsonArray =
        functionNamesLine.substring(functionNamesLine.indexOf(":") + 1);
    String typesAsJsonArray = typesLine.substring(typesLine.indexOf(":") + 1);

    Type stringListType = new TypeToken<List<String>>() {}.getType();

    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    List<String> localFileNames = gson.fromJson(fileNamesAsJsonArray, stringListType);
    List<String> functionNames = gson.fromJson(functionNamesAsJsonArray, stringListType);

    List<String> typesAsStringList = gson.fromJson(typesAsJsonArray, stringListType);
    List<InstrumentationPoint.Type> types =
        typesAsStringList.stream()
            .map(InstrumentationPoint.Type::valueOf)
            .collect(Collectors.toList());

    for (int i = 3; i < linesOfFile.size(); ++i) {
      String lineItem = linesOfFile.get(i);
      String id = lineItem.substring(0, lineItem.indexOf(':'));
      String encodedDetails = lineItem.substring(lineItem.indexOf(':') + 1);

      StringCharIterator encodedDetailsAsCharIt = new StringCharIterator(encodedDetails);

      InstrumentationPoint temp =
          InstrumentationPoint.newBuilder()
              .setFileName(localFileNames.get(Base64VLQ.decode(encodedDetailsAsCharIt)))
              .setFunctionName(functionNames.get(Base64VLQ.decode(encodedDetailsAsCharIt)))
              .setType(types.get(Base64VLQ.decode(encodedDetailsAsCharIt)))
              .setLineNumber(Base64VLQ.decode(encodedDetailsAsCharIt))
              .setColumnNumber(Base64VLQ.decode(encodedDetailsAsCharIt))
              .build();
      mapping.putIfAbsent(id, temp);
    }

    return mapping;
  }

  public static ReportProfile decodeReport(
      Map<String, InstrumentationPoint> mapping, Map<String, Long> frequencies) {
    // Decode each entry into InstrumentionPoint.
    Stream<InstrumentationPointStats> instrumentationPoints =
        mapping.entrySet().stream()
            .map(
                (entry) ->
                    InstrumentationPointStats.newBuilder()
                        .setPoint(entry.getValue())
                        .setTimesExecuted(frequencies.getOrDefault(entry.getKey(), 0L))
                        .build());

    return ReportDecoder.createReportProfile(instrumentationPoints);
  }

  /**
   * This function builds a report for given JS binary analyzing its content. If an instrumentation
   * point is present in the JS binary - it will be marked as executed once. If the instrumentation
   * point was removed by the compiler as dead code - it will be marked as executed zero times. This
   * function doesn't give 100% guarantee for finding unused code but it provides good enough
   * approximation to be useful.
   *
   * @param mapping Parsed instrumentation mapping. Use {@link #parseMapping} function to create it.
   * @param fileContent Content of a JS binary compiled with production instrumentation enabled.
   * @param instrumentationArrayName Name of the array that was passed as
   *     --production_instrumentation_array_name flag when compiling JS binary.
   * @return Report containing all instrumentation points. If a point times_executed is zero - it
   *     means the function or branch was removed as dead code. If it's one - it's present in the JS
   *     binary.
   */
  public static ReportProfile createProfileOfStaticallyUsedCode(
      Map<String, InstrumentationPoint> mapping,
      String fileContent,
      String instrumentationArrayName) {
    Matcher matcher =
        Pattern.compile(instrumentationArrayName + "\\.push\\(['\"](.*?)['\"]\\)")
            .matcher(fileContent);
    final HashSet<String> encodedPoints = new HashSet<>();
    while (matcher.find()) {
      encodedPoints.add(matcher.group(1));
    }
    Stream<InstrumentationPointStats> instrumentationPoints =
        mapping.entrySet().stream()
            .map(
                (entry) ->
                    InstrumentationPointStats.newBuilder()
                        .setPoint(entry.getValue())
                        .setTimesExecuted(encodedPoints.contains(entry.getKey()) ? 1L : 0L)
                        .build());
    return ReportDecoder.createReportProfile(instrumentationPoints);
  }

  /**
   * Given list of profiles merges them, summing up execution counts for instrumentation points used
   * across multiple profiles. Be aware that instrumentation points are compared as equality so
   * profiles should come from JS binaries compiled with at the same commit, otherwise changes in
   * source code will cause differences in some instrumentation points.
   */
  public static ReportProfile mergeProfiles(List<ReportProfile> profiles) {
    Map<InstrumentationPoint, Long> frequencies = new HashMap<>();
    for (ReportProfile profile : profiles) {
      for (FileProfile fileProfile : profile.getFileProfileList()) {
        for (InstrumentationPointStats pointStats :
            fileProfile.getInstrumentationPointsStatsList()) {
          frequencies.compute(
              pointStats.getPoint(),
              (p, executed) -> (executed == null ? 0 : executed) + pointStats.getTimesExecuted());
        }
      }
    }
    Stream<InstrumentationPointStats> finalPoints =
        frequencies.entrySet().stream()
            .map(
                (Map.Entry<InstrumentationPoint, Long> entry) ->
                    InstrumentationPointStats.newBuilder()
                        .setPoint(entry.getKey())
                        .setTimesExecuted(entry.getValue())
                        .build());
    return ReportDecoder.createReportProfile(finalPoints);
  }

  /**
   * Groups instrumentation points by files and convert them to FileProfile object, one per file and
   * return as ReportProfile.
   */
  private static ReportProfile createReportProfile(
      Stream<InstrumentationPointStats> instrumentationPoints) {
    List<FileProfile> fileProfiles =
        instrumentationPoints
            .sorted(
                comparing(
                        (InstrumentationPointStats pointStats) ->
                            pointStats.getPoint().getFileName())
                    .thenComparingInt(
                        (InstrumentationPointStats pointStats) ->
                            pointStats.getPoint().getLineNumber()))
            .collect(
                Collectors.groupingBy(
                    (InstrumentationPointStats pointStats) -> pointStats.getPoint().getFileName()))
            .entrySet()
            .stream()
            .map(
                (entry) ->
                    FileProfile.newBuilder()
                        .setFileName(entry.getKey())
                        .addAllInstrumentationPointsStats(entry.getValue())
                        .build())
            .sorted(comparing(FileProfile::getFileName))
            .collect(Collectors.toList());

    return ReportProfile.newBuilder().addAllFileProfile(fileProfiles).build();

  }

  /**
   * A implementation of the Base64VLQ CharIterator used for decoding the mappings encoded in the
   * JSON string.
   */
  private static class StringCharIterator implements Base64VLQ.CharIterator {

    final String content;
    final int length;
    int current = 0;

    StringCharIterator(String content) {
      this.content = content;
      this.length = content.length();
    }

    @Override
    public char next() {
      return content.charAt(current++);
    }

    @Override
    public boolean hasNext() {
      return current < length;
    }
  }
}
