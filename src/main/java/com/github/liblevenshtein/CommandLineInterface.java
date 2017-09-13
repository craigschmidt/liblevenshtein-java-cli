package com.github.liblevenshtein;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.google.common.base.Joiner;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import lombok.extern.slf4j.Slf4j;

import com.github.liblevenshtein.collection.dictionary.SortedDawg;
import com.github.liblevenshtein.serialization.BytecodeSerializer;
import com.github.liblevenshtein.serialization.PlainTextSerializer;
import com.github.liblevenshtein.serialization.ProtobufSerializer;
import com.github.liblevenshtein.serialization.Serializer;
import com.github.liblevenshtein.transducer.Algorithm;
import com.github.liblevenshtein.transducer.ITransducer;
import com.github.liblevenshtein.transducer.factory.TransducerBuilder;

/**
 * Command-line interface to liblevenshtein (Java).
 */
@Slf4j
@SuppressWarnings({"checkstyle:uncommentedmain", "checkstyle:classdataabstractioncoupling"})
public class CommandLineInterface extends Action {

  /**
   * Argument may be a filesystem path or Java-compatible URI.
   */
  private static final String ARG_PATH_OR_URI = "PATH|URI";

  /**
   * Argument must be an {@link Algorithm}.
   */
  private static final String ARG_ALGORITHM = "ALGORITHM";

  /**
   * Argument must be an {@link Integer}.
   */
  private static final String ARG_INTEGER = "INTEGER";

  /**
   * Argument must be a list of space-delimited strings, with at least one
   * value.
   */
  private static final String ARG_STRINGS = "STRING> <...";

  /**
   * Argument must be a filesystem path.
   */
  private static final String ARG_PATH = "PATH";

  /**
   * Argument must be a {@link SerializationFormat}.
   */
  private static final String ARG_FORMAT = "FORMAT";

  /**
   * Filesystem path or Java-compatible URI to a dictionary of terms.
   */
  private static final String FLAG_DICTIONARY = "dictionary";

  /**
   * Specifies that the dictionary is sorted lexicographically, in ascending
   * order.
   */
  private static final String FLAG_IS_SORTED = "is-sorted";

  /**
   * Levenshtein algorithm to use.
   */
  private static final String FLAG_ALGORITHM = "algorithm";

  /**
   * Maximun, Levenshtein distance a spelling candidate may be from the query
   * term.
   */
  private static final String FLAG_MAX_DISTANCE = "max-distance";

  /**
   * Include the Levenshtein distance with each spelling candidate.
   */
  private static final String FLAG_INCLUDE_DISTANCE = "include-distance";

  /**
   * Terms to query against the dictionary.
   */
  private static final String FLAG_QUERY = "query";

  /**
   * Path to save the serialized dictionary.
   */
  private static final String FLAG_SERIALIZE = "serialize";

  /**
   * Format of the source dictionary.
   */
  private static final String FLAG_SOURCE_FORMAT = "source-format";

  /**
   * Format of the serialized dictionary.
   */
  private static final String FLAG_TARGET_FORMAT = "target-format";

  /**
   * Colorize output.
   */
  private static final String FLAG_COLORIZE = "colorize";

  /**
   * Heuristic to distinguish between URIs and filesystem paths.
   */
  private static final Pattern RE_PROTO =
    Pattern.compile("^(?:[a-z]+:)*[a-z]+://.*$");

  /**
   * Default, Levenshtein algorithm to use for querying the dictionary.
   */
  private static final Algorithm DEFAULT_ALGORITHM = Algorithm.TRANSPOSITION;

  /**
   * Default, number of spelling errors to accept when querying the dictionary.
   */
  private static final int DEFAULT_MAX_DISTANCE = 2;

  /**
   * Default format for serializing dictionaries.
   */
  private static final SerializationFormat DEFAULT_FORMAT =
    SerializationFormat.PROTOBUF;

  /**
   * Joins elements with commas.
   */
  private static final Joiner COMMAS = Joiner.on(", ");

  /**
   * Joins elements with newlines.
   */
  private static final Joiner NEWLINES = Joiner.on("\n");

  /**
   * Constructs a new command-line interface with the arguments.
   * @param args Command-line arguments
   */
  public CommandLineInterface(final String[] args) {
    super(args);
  }

  /**
   * Specifies the name of the application for the help text.
   * @return Name of the application for the help text.
   */
  @Override
  protected String name() {
    return "liblevenshtein-java-cli";
  }

  /**
   * Specifies the header for the help documentation.
   * @return Header for the help documentation.
   */
  @Override
  @SuppressWarnings("checkstyle:multiplestringliterals")
  protected String helpHeader() {
    return String.format("%s%n%n", NEWLINES.join(
      "",
      "Command-Line Interface to liblevenshtein (Java)",
      "",
      "<" + ARG_FORMAT + "> specifies the serialization format of the dictionary,",
      "and may be one of the following:",
      "  1. " + SerializationFormat.PROTOBUF,
      "     - (de)serialize the dictionary as a protobuf stream.",
      "     - This is the preferred format.",
      "     - See: https://developers.google.com/protocol-buffers/",
      "  2. " + SerializationFormat.BYTECODE,
      "     - (de)serialize the dictionary as a Java, bytecode stream.",
      "  3. " + SerializationFormat.PLAIN_TEXT,
      "     - (de)serialize the dictionary as a plain text file.",
      "     - Terms are delimited by newlines.",
      "",
      "<" + ARG_ALGORITHM + "> specifies the Levenshtein algorithm to use for",
      "querying-against the dictionary, and may be one of the following:",
      "  1. " + Algorithm.STANDARD,
      "     - Use the standard, Levenshtein distance which considers the",
      "     following elementary operations:",
      "       o Insertion",
      "       o Deletion",
      "       o Substitution",
      "     - An elementary operation is an operation that incurs a penalty of",
      "     one unit.",
      "  2. " + Algorithm.TRANSPOSITION,
      "     - Extend the standard, Levenshtein distance to include transpositions",
      "     as elementary operations.",
      "       o A transposition is a swapping of two, consecutive characters as",
      "       follows: ba -> ab",
      "       o With the standard distance, this would require at least two",
      "       operations:",
      "         + An insertion and a deletion",
      "         + A deletion and an insertion",
      "         + Two substitutions",
      "  3. " + Algorithm.MERGE_AND_SPLIT,
      "     - Extend the standard, Levenshtein distance to include merges and",
      "     splits as elementary operations.",
      "       o A merge takes two characters and merges them into a single one.",
      "         + For example: ab -> c",
      "       o A split takes a single character and splits it into two others",
      "         + For example: a -> bc",
      "       o With the standard distance, these would require at least two",
      "       operations:",
      "         + Merge:",
      "           > A deletion and a substitution",
      "           > A substitution and a deletion",
      "         + Split:",
      "           > An insertion and a substitution",
      "           > A substitution and an insertion"));
  }

  /**
   * Specifies the footer for the help documentation.
   * @return Footer for the help documentation.
   */
  @Override
  protected String helpFooter() {
    return String.format(
      "%nExample: %s \\%n"
      + "  --algorithm TRANSPOSITION \\%n"
      + "  --max-distance 2 \\%n"
      + "  --include-distance \\%n"
      + "  --query mispelled mispelling \\%n"
      + "  --colorize", name());
  }

  /**
   * Stream to the dictionary to query against.  This may be any valid,
   * filesystem path or Java-compatible URI (such as a remote dictionary, Jar
   * resource, etc.).
   * @return Stream to the dictionary to query against.
   * @throws IOException If the dictionary stream cannot be read.
   */
  @SuppressWarnings("checkstyle:illegalcatch")
  private InputStream dictionary() throws IOException {
    final String path = cli.getOptionValue(FLAG_DICTIONARY);

    try {
      if (null == path && 0 != System.in.available()) {
        return System.in;
      }
    }
    catch (final IOException exception) {
      log.warn("Cannot read from <STDIN>");
    }

    if (null == path) {
      throw new IllegalArgumentException("No dictionary specified");
    }

    try {
      final URI uri = RE_PROTO.matcher(path).matches()
        ? new URI(path)
        : Paths.get(path).toUri();

      return uri.toURL().openStream();
    }
    catch (final Exception exception) {
      final String message =
        String.format("Failed to build dictionary from [%s]", path);
      throw new IllegalArgumentException(message, exception);
    }
  }

  /**
   * Specifies whether the dictionary is sorted (saves work if it is).
   * @return Whether the dictionary is sorted.
   */
  private boolean isSorted() {
    return cli.hasOption(FLAG_IS_SORTED);
  }

  /**
   * Levenshtein algorithm to use while querying the dictionary.
   * @return Levenshtein algorithm to use while querying the dictionary.
   */
  private Algorithm algorithm() {
    final String algorithmName = cli.getOptionValue(FLAG_ALGORITHM);

    if (null == algorithmName) {
      return DEFAULT_ALGORITHM;
    }

    for (final Algorithm algorithm : Algorithm.values()) {
      if (algorithm.name().equals(algorithmName)) {
        return algorithm;
      }
    }

    final String message =
      String.format("Unknown algorithm [%s], expected one of [%s]",
        algorithmName, COMMAS.join(Algorithm.values()));
    throw new IllegalArgumentException(message);
  }

  /**
   * Maximum-allowed, Levenshtein distance a spelling candidate may be from its
   * query term.
   * @return Maximum, Levenshtein distance of spelling candidates.
   */
  private int maxDistance() {
    final String maxDistance = cli.getOptionValue(FLAG_MAX_DISTANCE);

    if (null == maxDistance) {
      return DEFAULT_MAX_DISTANCE;
    }

    try {
      return Integer.parseInt(maxDistance);
    }
    catch (final NumberFormatException exception) {
      final String message =
        String.format("Expeted an integer for max-distance, but received [%s]",
          maxDistance);
      throw new IllegalArgumentException(message, exception);
    }
  }

  /**
   * Whether to include the number of errors from each query term, with the
   * spelling candidates.
   * @return Whether to include the Levenshtein distance.
   */
  private boolean includeDistance() {
    return cli.hasOption(FLAG_INCLUDE_DISTANCE);
  }

  /**
   * Terms to query against the dictionary.
   * @return Terms to query against the dictionary.
   */
  private List<String> queryTerms() {
    if (cli.hasOption(FLAG_QUERY)) {
      return Arrays.asList(cli.getOptionValues(FLAG_QUERY));
    }
    return Arrays.asList();
  }

  /**
   * Read the original dictonary terms, and use those as query terms
   * @return Terms to query against the dictionary.
   */
  private List<String> queryDictTerms() {
    // TODO: reads in from a plain text file only
    final String path = cli.getOptionValue(FLAG_DICTIONARY);
    ArrayList<String> queryTerms = new ArrayList<String>();
    try {
        // TODO: hardcoded path
        BufferedReader br = new BufferedReader(new FileReader(path));         

        String query;
        while ((query = br.readLine()) != null) {
            queryTerms.add(query);
        }
        br.close();

    } catch (IOException e) {
        e.printStackTrace();
    }
    return queryTerms;
  }

  /**
   * Where to serialize the dictionary.  This will be null if the dictionary
   * should not be serialzied.
   * @return Where to serialize the dictionary.
   */
  private Path serializationPath() {
    final String serializationPath = cli.getOptionValue(FLAG_SERIALIZE);
    if (null == serializationPath) {
      return null;
    }
    return Paths.get(serializationPath);
  }

  /**
   * Returns the source, serialization format for dictionaries (or null, if no
   * source format was specified).
   * @return Target, serialization format for dictionaries.
   */
  private SerializationFormat sourceFormat() {
    final String sourceFormat = cli.getOptionValue(FLAG_SOURCE_FORMAT);
    if (null == sourceFormat) {
      return null;
    }
    return SerializationFormat.valueOf(sourceFormat);
  }

  /**
   * Returns the target, serialization format for dictionaries
   * (or {@link #DEFAULT_FORMAT}, if no target format was specified).
   * @return Target, serialization format for dictionaries.
   */
  private SerializationFormat targetFormat() {
    final String targetFormat = cli.getOptionValue(FLAG_TARGET_FORMAT);
    if (null == targetFormat) {
      return DEFAULT_FORMAT;
    }
    return SerializationFormat.valueOf(targetFormat);
  }

  /**
   * Whether to colorize the output.
   * @return Whether to colorize the output.
   */
  private boolean colorize() {
    return cli.hasOption(FLAG_COLORIZE);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Options options() {
    final Options options = super.options();
    options.addOption(
      Option.builder("d")
        .longOpt(FLAG_DICTIONARY)
        .argName(ARG_PATH_OR_URI)
        .desc("Filesystem path or Java-compatible URI to a dictionary of terms")
        .hasArg()
        .build());
    options.addOption(
      Option.builder("s")
        .longOpt(FLAG_IS_SORTED)
        .desc("Specifies that the dictionary is sorted lexicographically, in "
          + "ascending order (Default: false)")
        .build());
    options.addOption(
      Option.builder("a")
        .longOpt(FLAG_ALGORITHM)
        .argName(ARG_ALGORITHM)
        .desc(String.format("Levenshtein algorithm to use (Default: %s)",
          DEFAULT_ALGORITHM))
        .hasArg()
        .build());
    options.addOption(
      Option.builder("m")
        .longOpt(FLAG_MAX_DISTANCE)
        .argName(ARG_INTEGER)
        .desc(String.format("Maximun, Levenshtein distance a spelling candidate"
          + "may be from the query term (Default: %d)", DEFAULT_MAX_DISTANCE))
        .hasArg()
        .build());
    options.addOption(
      Option.builder("i")
        .longOpt(FLAG_INCLUDE_DISTANCE)
        .desc("Include the Levenshtein distance with each spelling candidate "
          + "(Default: false)")
        .build());
    options.addOption(
      Option.builder("q")
        .longOpt(FLAG_QUERY)
        .argName(ARG_STRINGS)
        .desc("Terms to query against the dictionary.  You may specify multiple terms.")
        .hasArgs()
        .build());
    options.addOption(
      Option.builder()
        .longOpt(FLAG_SERIALIZE)
        .argName(ARG_PATH)
        .desc("Path to save the serialized dictionary")
        .hasArg()
        .build());
    options.addOption(
      Option.builder()
        .longOpt(FLAG_SOURCE_FORMAT)
        .argName(ARG_FORMAT)
        .desc("Format of the source dictionary (Default: adaptively-try each format until one works)")
        .hasArg()
        .build());
    options.addOption(
      Option.builder()
        .longOpt(FLAG_TARGET_FORMAT)
        .argName(ARG_FORMAT)
        .desc(String.format("Format of the serialized dictionary (Default: %s)",
          DEFAULT_FORMAT))
        .hasArg()
        .build());
    options.addOption(
      Option.builder()
        .longOpt(FLAG_COLORIZE)
        .desc("Colorize output")
        .build());
    return options;
  }

  /**
   * Deserializes the dictionary as the desired format.
   * @param serializer Deserializes the dictionary.
   * @return Deserialized dictionary.
   * @throws Exception When the dictionary cannot be deserialized.
   */
  private SortedDawg deserialize(final Serializer serializer) throws Exception {
    try (final InputStream stream = dictionary()) {
      return serializer.deserialize(SortedDawg.class, stream);
    }
  }

  /**
   * Deserialize the dictionary using the specified format.
   * @param format Serialization format of the dictionary stream.
   * @return Dictionary desized using the specified format.
   * @throws Exception When the dictionary cannot be deserialized as the given
   *   format.
   */
  private SortedDawg deserialize(final SerializationFormat format) throws Exception {
    switch (format) {
      case PROTOBUF:
        return deserialize(new ProtobufSerializer());
      case PLAIN_TEXT:
        if (isSorted()) {
          return deserialize(new PlainTextSerializer(true));
        }
        return deserialize(new PlainTextSerializer(false));
      case BYTECODE:
        return deserialize(new BytecodeSerializer());
      default:
        final String message = String.format("Unsupported format [%s]", format);
        throw new IllegalArgumentException(message);
    }
  }

  /**
   * Guess the content-type of the dictionary stream.
   * @return Content-type of the dictionary.
   * @throws IOException If the content-type cannot be guessed.
   */
  private String dictionaryContentType() throws IOException {
    Path tmp = null;

    try {
      tmp = Files.createTempFile("dictionary-", ".unknown");
      tmp.toFile().deleteOnExit();

      try (final InputStream stream = dictionary()) {
        Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
      }

      // Guess the content-type of the dictionary stream
      return Files.probeContentType(tmp);
    }
    finally {
      if (null != tmp) {
        Files.delete(tmp);
      }
    }
  }

  /**
   * Adaptively-deserializes the dictionary by trying each serializer until one
   * succeeds.
   * @return Dictionary from the first deserializer that succeeds.
   * @throws Exception If the dictionary cannot be deserialized.
   */
  @SuppressWarnings("checkstyle:illegalcatch")
  private SortedDawg deserializeAdaptive() throws Exception {
    for (final SerializationFormat format : SerializationFormat.values()) {
      try {
        log.info("Attempting to deserialize dictionary as a [{}] stream", format);
        return deserialize(format);
      }
      catch (final Exception exception) {
        log.warn("Nope, dictionary is not a [{}] stream", format);
      }
    }

    final String message =
      String.format(
        "Cannot read dictionary, which appears to have the content-type [%s].",
          dictionaryContentType());

    throw new IllegalStateException(message);
  }

  /**
   * Builds a new dictionary from the specified stream and whether it is sorted.
   * @return New dictionary, according to command-line arguments.
   * @throws Exception When the dictionary cannot be read from the stream.
   */
  @SuppressFBWarnings("REC_CATCH_EXCEPTION")
  @SuppressWarnings("checkstyle:illegalcatch")
  private SortedDawg buildDictionary() throws Exception {
    if (null == sourceFormat()) {
      return deserializeAdaptive();
    }

    try {
      return deserialize(sourceFormat());
    }
    catch (final Exception exception) {
      final String dictionaryContentType = dictionaryContentType();

      if (!dictionaryContentType.equals(sourceFormat().contentType())) {
        log.warn("Serialization format [{}] expects a content-type [{}], but "
            + "the dictionary appears to have the content-type [{}].",
            sourceFormat(), sourceFormat().contentType(),
            dictionaryContentType);
      }

      final String message =
        String.format("Failed to deserialize dictionary as type [%s].",
          sourceFormat());

      throw new IOException(message, exception);
    }
  }

  /**
   * Generates spelling candidates.
   * @param dictionary Spelling candidates to query.
   * @return Transducer of query terms to spelling candidates.
   */
  private ITransducer<Object> buildTransducer(final SortedDawg dictionary) {
    return new TransducerBuilder()
      .algorithm(algorithm())
      .defaultMaxDistance(maxDistance())
      .includeDistance(includeDistance())
      .dictionary(dictionary, true)
      .build();
  }

  /**
   * Prints headers.
   * @return Printer for headers.
   */
  private BiConsumer<StringBuilder, String> buildHeaderPrinter() {
    return colorize()
      ? new HeaderColorPrinter()
      : new HeaderPrinter();
  }

  /**
   * Prints spelling candidates.
   * @return Printer for spelling candidates.
   */
  private Printer buildCandidatePrinter() {
    return includeDistance()
      ? colorize()
        ? new CandidateColorPrinter()
        : new CandidatePrinter()
      : colorize()
        ? new StringColorPrinter()
        : new StringPrinter();
  }

  /**
   * Prints the results of querying the dictionary.
   * supress header printing for now
   * @param dictionary Spelling candidates to query.
   * @param queryTerms Query terms for the dictionary.
   */
  private void printResults(
      final SortedDawg dictionary,
      final List<String> queryTerms) {
    final ITransducer<Object> transducer = buildTransducer(dictionary);
    final Printer printer = buildCandidatePrinter();
    // final BiConsumer<StringBuilder, String> header = buildHeaderPrinter();

    final StringBuilder buffer = new StringBuilder(1024);

    for (final String queryTerm : queryTerms) {
      final String escapedQuery = StringEscapeUtils.escapeJava(queryTerm);
      // header.accept(buffer, escapedQuery);
      for (final Object object : transducer.transduce(queryTerm)) {
        printer.print(buffer, escapedQuery, object);
      }
    }
  }

  /**
   * Queries a dictionary to find all spelling candidates for a sequence of
   * query terms, according to the parameters specified on the command-line.
   */
  @Override
  protected void runInternal() throws Exception {
    final SortedDawg dictionary = buildDictionary();
    List<String> queryTerms = queryTerms();

    // if no terms then read in our dictionary to use that
    if (queryTerms.isEmpty()) {
        queryTerms = queryDictTerms();
    }

    printResults(dictionary, queryTerms);

    if (null != serializationPath()) {
      serialize(dictionary);
    }
  }


  /**
   * Serializes the dictionary to the desired location, as the specified format.
   * @param dictionary Dictionary to serialize.
   * @throws Exception If the dictionary cannot be serialized.
   */
  private void serialize(final SortedDawg dictionary) throws Exception {
    log.info("Serializing [{}] terms in the dictionary to [{}] as format [{}]",
        dictionary.size(),
        serializationPath(),
        targetFormat());

    switch (targetFormat()) {
      case PROTOBUF:
        serialize(dictionary, new ProtobufSerializer());
        break;
      case PLAIN_TEXT:
        if (isSorted()) {
          serialize(dictionary, new PlainTextSerializer(true));
        }
        else {
          serialize(dictionary, new PlainTextSerializer(false));
        }
        break;
      case BYTECODE:
        serialize(dictionary, new BytecodeSerializer());
        break;
      default:
        final String message = String.format(
          "Unsupported, serialization format [%s]",
            targetFormat());
        throw new IllegalArgumentException(message);
    }
  }

  /**
   * Serializes a dictionary to the desired location, as the specified format.
   * @param dictionary Dictionary to serialize.
   * @param serializer Serializes the dictionary as the speicified format.
   * @throws Exception If the dictionary cannot be serialized.
   */
  private void serialize(
      final SortedDawg dictionary,
      final Serializer serializer) throws Exception {
    try (final OutputStream stream = Files.newOutputStream(serializationPath())) {
      serializer.serialize(dictionary, stream);
    }
  }

  /**
   * Queries a dictionary to find all spelling candidates for a sequence of
   * query terms, according to the parameters specified on the command-line.
   * @param args Arguments that specify how to query the dictionary.
   */
  @SuppressWarnings("checkstyle:illegalcatch")
  public static void main(final String... args) {
    try {
      final CommandLineInterface app = new CommandLineInterface(args);
      app.run();
    }
    catch (final Throwable thrown) {
      log.error("Rescued unhandled exception while running application", thrown);
      System.exit(EXIT_UNHANDLED_ERROR);
    }
  }
}
