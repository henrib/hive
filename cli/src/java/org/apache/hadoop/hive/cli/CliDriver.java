/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 package org.apache.hadoop.hive.cli;

import static org.apache.hadoop.hive.shims.HadoopShims.USER_ID;
import static org.apache.hadoop.util.StringUtils.stringifyException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.HiveInterruptUtils;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.cli.EscapeCRLFHelper;
import org.apache.hadoop.hive.common.cli.ShellCmdExecutor;
import org.apache.hadoop.hive.common.io.CachingPrintStream;
import org.apache.hadoop.hive.common.io.FetchConverter;
import org.apache.hadoop.hive.common.io.SessionStream;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.conf.HiveVariableSource;
import org.apache.hadoop.hive.conf.Validator;
import org.apache.hadoop.hive.conf.VariableSubstitution;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.IDriver;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.mr.HadoopJobExecHelper;
import org.apache.hadoop.hive.ql.exec.tez.TezJobExecHelper;
import org.apache.hadoop.hive.ql.metadata.HiveMaterializedViewsRegistry;
import org.apache.hadoop.hive.ql.metadata.HiveMetaStoreClientWithLocalCache;
import org.apache.hadoop.hive.ql.parse.CalcitePlanner;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorException;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hive.common.util.HiveStringUtils;
import org.apache.hive.common.util.MatchingStringsCompleter;
import org.apache.hive.common.util.ShutdownHookManager;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

/**
 * CliDriver.
 *
 */
public class CliDriver {

  public static String prompt = null;
  public static String prompt2 = null; // when ';' is not yet seen
  public static final int LINES_TO_FETCH = 40; // number of lines to fetch in batch from remote hive server
  public static final int DELIMITED_CANDIDATE_THRESHOLD = 10;

  public static final String HIVERCFILE = ".hiverc";

  private final LogHelper console;
  protected LineReader reader;
  private Configuration conf;

  public CliDriver() {
    SessionState ss = SessionState.get();
    conf = (ss != null) ? ss.getConf() : new Configuration();
    Logger LOG = LoggerFactory.getLogger("CliDriver");
    LOG.debug("CliDriver inited with classpath {}", System.getProperty("java.class.path"));
    console = new LogHelper(LOG);
  }

  public CommandProcessorResponse processCmd(String cmd) throws CommandProcessorException {
    CliSessionState ss = (CliSessionState) SessionState.get();
    ss.setLastCommand(cmd);
    // Flush the print stream, so it doesn't include output from the last command
    ss.err.flush();
    try {
      ss.updateThreadName();
      return processCmd1(cmd);
    } finally {
      ss.resetThreadName();
    }
  }

  public CommandProcessorResponse processCmd1(String cmd) throws CommandProcessorException {
    CliSessionState ss = (CliSessionState) SessionState.get();

    String cmd_trimmed = HiveStringUtils.removeComments(cmd).trim();
    String[] tokens = tokenizeCmd(cmd_trimmed);
    CommandProcessorResponse response = new CommandProcessorResponse();

    if (cmd_trimmed.toLowerCase().equals("quit") || cmd_trimmed.toLowerCase().equals("exit")) {

      // if we have come this far - either the previous commands
      // are all successful or this is command line. in either case
      // this counts as a successful run
      ss.close();
      ExitUtil.terminate(0);

    } else if (tokens[0].equalsIgnoreCase("source")) {
      String cmd_1 = getFirstCmd(cmd_trimmed, tokens[0].length());
      cmd_1 = new VariableSubstitution(new HiveVariableSource() {
        @Override
        public Map<String, String> getHiveVariable() {
          return SessionState.get().getHiveVariables();
        }
      }).substitute(ss.getConf(), cmd_1);

      File sourceFile = new File(cmd_1);
      if (! sourceFile.isFile()){
        console.printError("File: "+ cmd_1 + " is not a file.");
        throw new CommandProcessorException(1);
      } else {
        try {
          response = processFile(cmd_1);
        } catch (IOException e) {
          console.printError("Failed processing file "+ cmd_1 +" "+ e.getLocalizedMessage(),
            stringifyException(e));
          throw new CommandProcessorException(1);
        }
      }
    } else if (cmd_trimmed.startsWith("!")) {
      // for shell commands, use unstripped command
      String shell_cmd = cmd.trim().substring(1);
      shell_cmd = new VariableSubstitution(new HiveVariableSource() {
        @Override
        public Map<String, String> getHiveVariable() {
          return SessionState.get().getHiveVariables();
        }
      }).substitute(ss.getConf(), shell_cmd);

      // shell_cmd = "/bin/bash -c \'" + shell_cmd + "\'";
      try {
        ShellCmdExecutor executor = new ShellCmdExecutor(shell_cmd, ss.out, ss.err);
        int responseCode = executor.execute();
        if (responseCode != 0) {
          console.printError("Command failed with exit code = " + response);
          ss.resetThreadName();
          throw new CommandProcessorException(responseCode);
        }
        response = new CommandProcessorResponse();
      } catch (Exception e) {
        console.printError("Exception raised from Shell command " + e.getLocalizedMessage(),
            stringifyException(e));
        throw new CommandProcessorException(1);
      }
    }  else { // local mode
      try {

        try (CommandProcessor proc = CommandProcessorFactory.get(tokens, (HiveConf) conf)) {
          if (proc instanceof IDriver) {
            // Let Driver strip comments using sql parser
            response = processLocalCmd(cmd, proc, ss);
          } else {
            response = processLocalCmd(cmd_trimmed, proc, ss);
          }
        }
      } catch (SQLException e) {
        console.printError("Failed processing command " + tokens[0] + " " + e.getLocalizedMessage(),
          org.apache.hadoop.util.StringUtils.stringifyException(e));
        throw new CommandProcessorException(1);
      } catch (CommandProcessorException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    return response;
  }

  /**
   * For testing purposes to inject Configuration dependency
   * @param conf to replace default
   */
  void setConf(Configuration conf) {
    this.conf = conf;
  }

  /**
   * Extract and clean up the first command in the input.
   */
  private String getFirstCmd(String cmd, int length) {
    return cmd.substring(length).trim();
  }

  private String[] tokenizeCmd(String cmd) {
    return cmd.split("\\s+");
  }

  CommandProcessorResponse processLocalCmd(String cmd, CommandProcessor proc, CliSessionState ss)
      throws CommandProcessorException {
    boolean escapeCRLF = HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_ESCAPE_CRLF);
    CommandProcessorResponse response = new CommandProcessorResponse();

    if (proc != null) {
      if (proc instanceof IDriver) {
        IDriver qp = (IDriver) proc;
        PrintStream out = ss.out;
        long start = System.currentTimeMillis();
        if (ss.getIsVerbose()) {
          out.println(cmd);
        }

        // Set HDFS CallerContext to queryId and reset back to sessionId after the query is done
        ShimLoader.getHadoopShims()
            .setHadoopQueryContext(String.format(USER_ID, qp.getQueryState().getQueryId(), ss.getUserName()));
        try {
          response = qp.run(cmd);
        } catch (CommandProcessorException e) {
          qp.close();
          ShimLoader.getHadoopShims()
              .setHadoopSessionContext(String.format(USER_ID, ss.getSessionId(), ss.getUserName()));
          throw e;
        }

        // query has run capture the time
        long end = System.currentTimeMillis();
        double timeTaken = (end - start) / 1000.0;

        ArrayList<String> res = new ArrayList<String>();

        printHeader(qp, out);

        // print the results
        int counter = 0;
        try {
          if (out instanceof FetchConverter) {
            ((FetchConverter) out).fetchStarted();
          }
          while (qp.getResults(res)) {
            for (String r : res) {
                  if (escapeCRLF) {
                    r = EscapeCRLFHelper.escapeCRLF(r);
                  }
              out.println(r);
            }
            counter += res.size();
            res.clear();
            if (out.checkError()) {
              break;
            }
          }
        } catch (IOException e) {
          console.printError("Failed with exception " + e.getClass().getName() + ":" + e.getMessage(),
              "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
          throw new CommandProcessorException(1);
        } finally {
          qp.close();
          ShimLoader.getHadoopShims()
              .setHadoopSessionContext(String.format(USER_ID, ss.getSessionId(), ss.getUserName()));

          if (out instanceof FetchConverter) {
            ((FetchConverter) out).fetchFinished();
          }

          console.printInfo(
              "Time taken: " + timeTaken + " seconds" + (counter == 0 ? "" : ", Fetched: " + counter + " row(s)"));
        }
      } else {
        String firstToken = tokenizeCmd(cmd.trim())[0];
        String cmd_1 = getFirstCmd(cmd.trim(), firstToken.length());

        if (ss.getIsVerbose()) {
          ss.out.println(firstToken + " " + cmd_1);
        }

        try {
          CommandProcessorResponse res = proc.run(cmd_1);
          if (res.getMessage() != null) {
            console.printInfo(res.getMessage());
          }
          return res;
        } catch (CommandProcessorException e) {
          ss.out.println("Query returned non-zero code: " + e.getResponseCode() + ", cause: " + e.getMessage());
          throw e;
        }
      }
    }
    return response;
  }

  /**
   * If enabled and applicable to this command, print the field headers
   * for the output.
   *
   * @param qp Driver that executed the command
   * @param out PrintStream which to send output to
   */
  private void printHeader(IDriver qp, PrintStream out) {
    List<FieldSchema> fieldSchemas = qp.getSchema().getFieldSchemas();
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_HEADER)
          && fieldSchemas != null) {
      // Print the column names
      boolean first_col = true;
      for (FieldSchema fs : fieldSchemas) {
        if (!first_col) {
          out.print('\t');
        }
        out.print(fs.getName());
        first_col = false;
      }
      out.println();
    }
  }

  public CommandProcessorResponse processLine(String line) throws CommandProcessorException {
    return processLine(line, false);
  }

  /**
   * Processes a line of semicolon separated commands
   *
   * @param line
   *          The commands to process
   * @param allowInterrupting
   *          When true the function will handle SIG_INT (Ctrl+C) by interrupting the processing and
   *          returning -1
   * @return 0 if ok
   */
  public CommandProcessorResponse processLine(String line, boolean allowInterrupting) throws CommandProcessorException {
    SignalHandler oldSignal = null;
    Signal interruptSignal = null;

    if (allowInterrupting) {
      // Remember all threads that were running at the time we started line processing.
      // Hook up the custom Ctrl+C handler while processing this line
      interruptSignal = Terminal.Signal.INT;
      oldSignal = reader.getTerminal().handle(interruptSignal, new SignalHandler() {
        private boolean interruptRequested;

        @Override
        public void handle(Signal signal) {
          boolean initialRequest = !interruptRequested;
          interruptRequested = true;

          // Kill the VM on second ctrl+c
          if (!initialRequest) {
            console.printInfo("Exiting the JVM");
            ExitUtil.terminate(127);
          }

          // Interrupt the CLI thread to stop the current statement and return
          // to prompt
          console.printInfo("Interrupting... Be patient, this might take some time.");
          console.printInfo("Press Ctrl+C again to kill JVM");

          // First, kill any running MR jobs
          HadoopJobExecHelper.killRunningJobs();
          TezJobExecHelper.killRunningJobs();
          HiveInterruptUtils.interrupt();
        }
      });
    }

    try {
      CommandProcessorResponse lastRet = new CommandProcessorResponse();
      CommandProcessorResponse ret;

      // we can not use "split" function directly as ";" may be quoted
      List<String> commands = splitSemiColon(line);

      StringBuilder command = new StringBuilder();
      for (String oneCmd : commands) {

        if (StringUtils.endsWith(oneCmd, "\\")) {
          command.append(StringUtils.chop(oneCmd) + ";");
          continue;
        } else {
          command.append(oneCmd);
        }
        if (StringUtils.isBlank(command.toString())) {
          continue;
        }

        try {
          ret = processCmd(command.toString());
          lastRet = ret;
        } catch (CommandProcessorException e) {
          boolean ignoreErrors = HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLI_IGNORE_ERRORS);
          if (!ignoreErrors) {
            throw e;
          }
        } finally {
          command.setLength(0);
        }
      }
      return lastRet;
    } finally {
      // Once we are done processing the line, restore the old handler
      if (oldSignal != null) {
        reader.getTerminal().handle(interruptSignal, oldSignal);
      }
    }
  }

  /**
   * Split the line by semicolon by ignoring the ones in the single/double quotes.
   *
   */
  public static List<String> splitSemiColon(String line) {
    boolean inQuotes = false;
    boolean escape = false;

    List<String> ret = new ArrayList<>();

    char quoteChar = '"';
    int beginIndex = 0;
    for (int index = 0; index < line.length(); index++) {
      char c = line.charAt(index);
      switch (c) {
      case ';':
        if (!inQuotes) {
          ret.add(line.substring(beginIndex, index));
          beginIndex = index + 1;
        }
        break;
      case '"':
      case '`':
      case '\'':
        if (!escape) {
          if (!inQuotes) {
            quoteChar = c;
            inQuotes = !inQuotes;
          } else {
            if (c == quoteChar) {
              inQuotes = !inQuotes;
            }
          }
        }
        break;
      default:
        break;
      }

      if (escape) {
        escape = false;
      } else if (c == '\\') {
        escape = true;
      }
    }

    if (beginIndex < line.length()) {
      ret.add(line.substring(beginIndex));
    }

    return ret;
  }

  public CommandProcessorResponse processReader(BufferedReader r) throws IOException, CommandProcessorException {
    String line;
    StringBuilder qsb = new StringBuilder();

    while ((line = r.readLine()) != null) {
      // Skipping through comments
      if (! line.startsWith("--")) {
        qsb.append(line + "\n");
      }
    }

    return (processLine(qsb.toString()));
  }

  public CommandProcessorResponse processFile(String fileName) throws IOException, CommandProcessorException {
    Path path = new Path(fileName);
    FileSystem fs;
    if (!path.toUri().isAbsolute()) {
      fs = FileSystem.getLocal(conf);
      path = fs.makeQualified(path);
    } else {
      fs = FileSystem.get(path.toUri(), conf);
    }
    BufferedReader bufferReader = null;

    try {
      bufferReader = new BufferedReader(new InputStreamReader(fs.open(path), StandardCharsets.UTF_8));
      return processReader(bufferReader);
    } finally {
      IOUtils.closeStream(bufferReader);
    }
  }

  public void processInitFiles(CliSessionState ss) throws IOException, CommandProcessorException {
    boolean saveSilent = ss.getIsSilent();
    ss.setIsSilent(true);
    for (String initFile : ss.initFiles) {
      processFileExitOnFailure(initFile);
    }
    if (ss.initFiles.size() == 0) {
      if (System.getenv("HIVE_HOME") != null) {
        String hivercDefault = System.getenv("HIVE_HOME") + File.separator +
          "bin" + File.separator + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          processFileExitOnFailure(hivercDefault);
          console.printError("Putting the global hiverc in " +
                             "$HIVE_HOME/bin/.hiverc is deprecated. Please "+
                             "use $HIVE_CONF_DIR/.hiverc instead.");
        }
      }
      if (System.getenv("HIVE_CONF_DIR") != null) {
        String hivercDefault = System.getenv("HIVE_CONF_DIR") + File.separator
          + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          processFileExitOnFailure(hivercDefault);
        }
      }
      if (System.getProperty("user.home") != null) {
        String hivercUser = System.getProperty("user.home") + File.separator +
          HIVERCFILE;
        if (new File(hivercUser).exists()) {
          processFileExitOnFailure(hivercUser);
        }
      }
    }
    ss.setIsSilent(saveSilent);
  }

  private void processFileExitOnFailure(String fileName) throws IOException {
    try {
      processFile(fileName);
    } catch (CommandProcessorException e) {
      ExitUtil.terminate(e.getResponseCode());
    }
  }

  private void processLineExitOnFailure(String command) throws IOException {
    try {
      processLine(command);
    } catch (CommandProcessorException e) {
      ExitUtil.terminate(e.getResponseCode());
    }
  }

  public void processSelectDatabase(CliSessionState ss) throws IOException, CommandProcessorException {
    String database = ss.database;
    if (database != null) {
      processLineExitOnFailure("use " + database + ";");
    }
  }

  public static Completer[] getCommandCompleter() {
    // StringsCompleter matches against a pre-defined wordlist
    // We start with an empty wordlist and build it up
    List<String> candidateStrings = new ArrayList<>();

    // We add Hive function names
    // For functions that aren't infix operators, we add an open
    // parenthesis at the end.
    for (String s : FunctionRegistry.getFunctionNames()) {
      if (s.matches("[a-z_]+")) {
        candidateStrings.add(s + "(");
      } else {
        candidateStrings.add(s);
      }
    }

    // We add Hive keywords, including lower-cased versions
    for (String s : HiveParser.getKeywords()) {
      candidateStrings.add(s);
      candidateStrings.add(s.toLowerCase());
    }

    Completer strCompleter = new MatchingStringsCompleter(candidateStrings);

    // The ArgumentCompleter allows us to match multiple tokens
    // in the same line.
    final ArgumentCompleter argCompleter = new ArgumentCompleter(strCompleter);
    // By default ArgumentCompleter is in "strict" mode meaning
    // a token is only auto-completed if all prior tokens
    // match. We don't want that since there are valid tokens
    // that are not in our wordlist (eg. table and column names)
    argCompleter.setStrict(false);

    // ArgumentCompleter always adds a space after a matched token.
    // This is undesirable for function names because a space after
    // the opening parenthesis is unnecessary (and uncommon) in Hive.
    // We stack a custom Completer on top of our ArgumentCompleter
    // to reverse this.
    Completer customCompleter = (reader, line, candidates) -> {
      argCompleter.complete(reader, line, candidates);
      candidates.forEach(System.out::println);
      // ConsoleReader will do the substitution if and only if there
      // is exactly one valid completion, so we ignore other cases.
      if (candidates.size() == 1) {
        String candidateStr = candidates.get(0).value();
        if (candidateStr.endsWith("( ")) {
          candidates.set(0, new Candidate(candidateStr.trim()));
        }
      }
    };

    List<String> vars = new ArrayList<String>();
    for (HiveConf.ConfVars conf : HiveConf.ConfVars.values()) {
      vars.add(conf.varname);
    }

    Completer confCompleter = new MatchingStringsCompleter(vars) {
      @Override
      public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        super.complete(reader, line, candidates);
        final int cursor = line.cursor();
        if (candidates.isEmpty() && cursor > 1 && line.word().charAt(cursor - 1) == '=') {
          HiveConf.ConfVars confVars = HiveConf.getConfVars(line.word().substring(0, cursor - 1));
          if (confVars == null) {
            return;
          }
          if (confVars.getValidator() instanceof Validator.StringSet) {
            Validator.StringSet validator = (Validator.StringSet)confVars.getValidator();
            validator.getExpected().stream().map(Candidate::new).forEach(candidates::add);
          } else if (confVars.getValidator() != null) {
            candidates.add(new Candidate(confVars.getValidator().toDescription()));
          } else {
            candidates.add(new Candidate("Expects " + confVars.typeString() + " type value"));
          }
          return;
        }
        if (candidates.size() > DELIMITED_CANDIDATE_THRESHOLD) {
          Set<Candidate> delimited = new LinkedHashSet<>();
          for (Candidate candidate : candidates) {
            Iterator<String> it = Splitter.on(".").split(
                candidate.value().subSequence(cursor, candidate.value().length())).iterator();
            if (it.hasNext()) {
              String next = it.next();
              if (next.isEmpty()) {
                next = ".";
              }
              candidate = new Candidate(line.line() != null ? line.line().substring(0, cursor) + next : next);
            }
            delimited.add(candidate);
          }
          candidates.clear();
          candidates.addAll(delimited);
        }
      }
    };

    Completer setCompleter = new MatchingStringsCompleter("set");

    ArgumentCompleter propCompleter = new ArgumentCompleter(setCompleter, confCompleter) {
      @Override
      public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        super.complete(reader, line, candidates);
        if (candidates.size() == 1) {
          candidates.set(0, new Candidate(candidates.get(0).value().trim()));
        }
      }
    };
    return new Completer[] {propCompleter, customCompleter};
  }

  public static void main(String[] args) throws Exception {
    int ret = new CliDriver().run(args);
    ExitUtil.terminate(ret);
  }

  public int run(String[] args) throws Exception {

    OptionsProcessor oproc = new OptionsProcessor();
    if (!oproc.process_stage1(args)) {
      return 1;
    }

    // NOTE: It is critical to do this here so that log4j is reinitialized
    // before any of the other core hive classes are loaded
    boolean logInitFailed = false;
    String logInitDetailMessage;
    try {
      logInitDetailMessage = LogUtils.initHiveLog4j();
    } catch (LogInitializationException e) {
      logInitFailed = true;
      logInitDetailMessage = e.getMessage();
    }

    CliSessionState ss = new CliSessionState(getConf());
    ss.in = System.in;
    try {
      ss.out =
          new SessionStream(System.out, true, StandardCharsets.UTF_8.name());
      ss.info =
          new SessionStream(System.err, true, StandardCharsets.UTF_8.name());
      ss.err = new CachingPrintStream(System.err, true,
          StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      return 3;
    }

    if (!oproc.process_stage2(ss)) {
      return 2;
    }

    if (!ss.getIsSilent()) {
      if (logInitFailed) {
        System.err.println(logInitDetailMessage);
      } else {
        SessionState.getConsole().printInfo(logInitDetailMessage);
      }
    }

    // set all properties specified via command line
    HiveConf conf = ss.getConf();
    for (Map.Entry<Object, Object> item : ss.cmdProperties.entrySet()) {
      conf.set((String) item.getKey(), (String) item.getValue());
      ss.getOverriddenConfigurations().put((String) item.getKey(), (String) item.getValue());
    }

    // read prompt configuration and substitute variables.
    prompt = conf.getVar(HiveConf.ConfVars.CLI_PROMPT);
    prompt = new VariableSubstitution(new HiveVariableSource() {
      @Override
      public Map<String, String> getHiveVariable() {
        return SessionState.get().getHiveVariables();
      }
    }).substitute(conf, prompt);
    prompt2 = spacesForString(prompt);

    if (HiveConf.getBoolVar(conf, ConfVars.HIVE_CLI_TEZ_SESSION_ASYNC)) {
      // Start the session in a fire-and-forget manner. When the asynchronously initialized parts of
      // the session are needed, the corresponding getters and other methods will wait as needed.
      SessionState.beginStart(ss, console);
    } else {
      SessionState.start(ss);
    }

    ss.updateThreadName();

    // Initialize metadata provider class and trimmer
    CalcitePlanner.warmup();
    // Create views registry
    HiveMaterializedViewsRegistry.get().init();

    // init metastore client cache
    if (HiveConf.getBoolVar(conf, ConfVars.MSC_CACHE_ENABLED)) {
      HiveMetaStoreClientWithLocalCache.init(conf);
    }

    // execute cli driver work
    try {
      executeDriver(ss, conf, oproc);
      return 0;
    } catch (CommandProcessorException e) {
      return e.getResponseCode();
    } finally {
      SessionState.endStart(ss);
      ss.resetThreadName();
      ss.close();
    }
  }

  protected HiveConf getConf() {
    return new HiveConf(SessionState.class);
  }

  /**
   * Execute the cli work
   * @param ss CliSessionState of the CLI driver
   * @param conf HiveConf for the driver session
   * @param oproc Operation processor of the CLI invocation
   * @return status of the CLI command execution
   * @throws Exception
   */
  private CommandProcessorResponse executeDriver(CliSessionState ss, HiveConf conf, OptionsProcessor oproc)
      throws Exception {

    CliDriver cli = newCliDriver();
    cli.setHiveVariables(oproc.getHiveVariables());

    // use the specified database if specified
    cli.processSelectDatabase(ss);

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(ss);

    if (ss.execString != null) {
      return cli.processLine(ss.execString);
    }

    try {
      if (ss.fileName != null) {
        return cli.processFile(ss.fileName);
      }
    } catch (FileNotFoundException e) {
      System.err.println("Could not open input file for reading. (" + e.getMessage() + ")");
      throw new CommandProcessorException(3);
    }
    if ("mr".equals(HiveConf.getVar(conf, ConfVars.HIVE_EXECUTION_ENGINE))) {
      console.printInfo(HiveConf.generateMrDeprecationWarning());
    }

    setupLineReader();

    String line;
    CommandProcessorResponse response = new CommandProcessorResponse();
    StringBuilder prefix = new StringBuilder();
    String curDB = getFormattedDb(conf, ss);
    String curPrompt = prompt + curDB;
    String dbSpaces = spacesForString(curDB);

    while ((line = reader.readLine(curPrompt + "> ")) != null) {
      if (!prefix.toString().equals("")) {
        prefix.append('\n');
      }
      if (line.trim().startsWith("--")) {
        continue;
      }
      if (line.trim().endsWith(";") && !line.trim().endsWith("\\;")) {
        line = prefix + line;
        response = cli.processLine(line, true);
        prefix.setLength(0);;
        curDB = getFormattedDb(conf, ss);
        curPrompt = prompt + curDB;
        dbSpaces = dbSpaces.length() == curDB.length() ? dbSpaces : spacesForString(curDB);
      } else {
        prefix.append(line);
        curPrompt = prompt2 + dbSpaces;
        continue;
      }
    }

    return response;
  }

  protected CliDriver newCliDriver() {
    return new CliDriver();
  }

  private String setupCmdHistory() {
    final String HISTORYFILE = ".hivehistory";
    String historyDirectory = System.getProperty("user.home");
    try {
      if ((new File(historyDirectory)).exists()) {
        return historyDirectory + File.separator + HISTORYFILE;
      } else {
        System.err.println("WARNING: Directory for Hive history file: " + historyDirectory +
                           " does not exist.   History will not be available during this session.");
      }
    } catch (Exception e) {
      System.err.println("WARNING: Encountered an error while trying to initialize Hive's " +
                         "history file.  History will not be available during this session.");
      System.err.println(e.getMessage());
      return null;
    }

    // add shutdown hook to flush the history to history file
    ShutdownHookManager.addShutdownHook(() -> {
      History h = reader.getHistory();
      try {
        h.save();
      } catch (IOException e) {
        System.err.println("WARNING: Failed to write command history file: " + e.getMessage());
      }
    });
    return null;
  }

  protected void setupLineReader() throws IOException {
    LineReaderBuilder builder = LineReaderBuilder.builder();
    builder.variable(LineReader.BELL_STYLE, "audible");
    Arrays.stream(getCommandCompleter()).forEach(builder::completer);
    builder.terminal(TerminalBuilder.terminal());
    builder.parser(getDefaultParser());
    builder.history(new DefaultHistory());

    String historyFile = setupCmdHistory();
    if (historyFile != null) {
      builder.variable(LineReader.HISTORY_FILE, historyFile);
    }
    reader = builder.build();
  }

  static DefaultParser getDefaultParser() {
    return new DefaultParser() {
      @Override
      public boolean isDelimiterChar(CharSequence buffer, int pos) {
        // Because we use parentheses in addition to whitespace
        // as a keyword delimiter, we need to define a new ArgumentDelimiter
        // that recognizes parenthesis as a delimiter.
        final char c = buffer.charAt(pos);
        return (Character.isWhitespace(c) || c == '(' || c == ')' ||
                c == '[' || c == ']');
      }
    };
  }

  /**
   * Retrieve the current database name string to display, based on the
   * configuration value.
   * @param conf storing whether or not to show current db
   * @param ss CliSessionState to query for db name
   * @return String to show user for current db value
   */
  private static String getFormattedDb(HiveConf conf, CliSessionState ss) {
    if (!HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLI_PRINT_CURRENT_DB)) {
      return "";
    }
    //BUG: This will not work in remote mode - HIVE-5153
    String currDb = SessionState.get().getCurrentDatabase();

    if (currDb == null) {
      return "";
    }

    return " (" + currDb + ")";
  }

  /**
   * Generate a string of whitespace the same length as the parameter
   *
   * @param s String for which to generate equivalent whitespace
   * @return  Whitespace
   */
  private static String spacesForString(String s) {
    if (s == null || s.length() == 0) {
      return "";
    }
    return String.format("%1$-" + s.length() +"s", "");
  }

  public void setHiveVariables(Map<String, String> hiveVariables) {
    SessionState.get().setHiveVariables(hiveVariables);
  }
}
