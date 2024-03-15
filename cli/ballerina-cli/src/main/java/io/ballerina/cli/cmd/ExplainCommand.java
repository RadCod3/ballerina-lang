package io.ballerina.cli.cmd;

import io.ballerina.cli.BLauncherCmd;
import io.ballerina.cli.TaskExecutor;
import io.ballerina.cli.task.CleanTargetDirTask;
import io.ballerina.cli.task.ResolveMavenDependenciesTask;
import io.ballerina.cli.task.RunBallerinaPreBuildToolsTask;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.Project;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.tools.diagnostics.DiagnosticCode;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static io.ballerina.cli.cmd.Constants.EXPLAIN_COMMAND;
import static io.ballerina.projects.util.ProjectUtils.findProjectRoot;
import static io.ballerina.projects.util.ProjectUtils.isProjectUpdated;

@CommandLine.Command(name = EXPLAIN_COMMAND, description = "Explain Ballerina error codes")
public class ExplainCommand implements BLauncherCmd {

    private static final String COMPILER_ERROR_PREFIX = "BCE";
    private PrintStream outStream;
    private PrintStream errStream;
    @CommandLine.Parameters
    private List<String> argList;

    @CommandLine.Option(names = {"--help", "-h"}, hidden = true)
    private boolean helpFlag;

    public ExplainCommand() {
        this.outStream = System.out;
        this.errStream = System.err;
    }

    public ExplainCommand(PrintStream outStream, PrintStream errStream) {
        this.outStream = outStream;
        this.errStream = errStream;
    }

    @Override
    public void execute() {
        if (helpFlag) {
            String commandUsageInfo = BLauncherCmd.getCommandUsageInfo(EXPLAIN_COMMAND);
            outStream.println(commandUsageInfo);
            return;
        }

        if (argList == null || argList.isEmpty()) {
            CommandUtil.printError(this.errStream, "no error code given", "bal explain <error-code> ", false);
            CommandUtil.exitError(true);
            return;
        }

        if (argList.size() > 1) {
            CommandUtil.printError(this.errStream, "too many arguments", "bal explain <error-code> ", false);
            CommandUtil.exitError(true);
            return;
        }

        String errorCode = argList.get(0);
        if (errorCode.isEmpty()) {
            CommandUtil.printError(this.errStream, "error code is empty", "bal explain <error-code> ", false);
            CommandUtil.exitError(true);
            return;
        }

        if (errorCode.startsWith(COMPILER_ERROR_PREFIX)) {
            explainErrorCode(errorCode);
        }

        Path currentPath = Paths.get(System.getProperty("user.dir"));
        Path projectRoot = findProjectRoot(currentPath);
        if (projectRoot != null) {
            outStream.println("Ballerina project detected at: " + projectRoot);
            Project project;
            BuildOptions buildOptions = BuildOptions.builder().targetDir(projectRoot.toString()).build();
            project = BuildProject.load(projectRoot, buildOptions);
            boolean isPackageModified = isProjectUpdated(project);
            TaskExecutor taskExecutor = new TaskExecutor.TaskBuilder()
                    // clean target dir for projects
                    .addTask(new CleanTargetDirTask(isPackageModified, buildOptions.enableCache()), false)
                    // Run build tools
                    .addTask(new RunBallerinaPreBuildToolsTask(outStream))
                    // resolve maven dependencies in Ballerina.toml
                    .addTask(new ResolveMavenDependenciesTask(outStream)).build();
            try {
                taskExecutor.executeTasks(project);
                outStream.println(project.currentPackage().packageName());
                project.currentPackage().dependencyManifest().packages().forEach(pkg -> {
                    outStream.println(pkg.name());
                });
            } catch (NullPointerException e) {
                outStream.println("Error occurred while executing the explain command");
            }

        } else {
            outStream.println("Ballerina project not detected at: " + currentPath);
        }

    }

    @Override
    public String getName() {
        return EXPLAIN_COMMAND;
    }

    @Override
    public void printLongDesc(StringBuilder out) {
        out.append(BLauncherCmd.getCommandUsageInfo(EXPLAIN_COMMAND));
    }

    @Override
    public void printUsage(StringBuilder out) {
        out.append("  bal explain <error-code> \n");
    }

    @Override
    public void setParentCmdParser(CommandLine parentCmdParser) {

    }

    private void explainErrorCode(String errorCode) {
        DiagnosticCode diagnosticCode = getDiagnosticError(errorCode);
        if (diagnosticCode == null) {
            outStream.println("Invalid error code: " + errorCode);
            return;
        }

        String notFoundMsg = String.format("Error code: %s - \"%s\" does not have an explanation", errorCode,
                diagnosticCode.messageKey());

        try {
            Path explanationPath =
                    Paths.get(ClassLoader.getSystemResource("error-codes").toURI()).resolve(errorCode + ".md");
            if (!Files.exists(explanationPath)) {
                outStream.println(notFoundMsg);
                return;
            }
            String explanation = Files.readString(explanationPath);

            outStream.println(explanation);
        } catch (IOException | URISyntaxException e) {
            outStream.println(notFoundMsg);
        }
    }

    private DiagnosticCode getDiagnosticError(String errorCode) {
        for (DiagnosticCode code : io.ballerina.compiler.internal.diagnostics.DiagnosticErrorCode.values()) {
            if (code.diagnosticId().equals(errorCode)) {
                return code;
            }
        }
        for (DiagnosticCode code : org.ballerinalang.util.diagnostic.DiagnosticErrorCode.values()) {
            if (code.diagnosticId().equals(errorCode)) {
                return code;
            }
        }

        return null;
    }
}
