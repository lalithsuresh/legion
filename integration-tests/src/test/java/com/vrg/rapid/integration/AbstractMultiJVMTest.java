package com.vrg.rapid.integration;

import com.vrg.standalone.StandaloneAgent;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.nio.file.Paths;

import static java.nio.file.Files.write;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.io.Files;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Assert;

/**
 * AbstractMultiJVMTest for running rapid agents.
 */
public class AbstractMultiJVMTest {

    // Get Rapid StandAlone runner jar path.
    private static String RAPID_RUNNER_JAR = StandaloneAgent.class.getProtectionDomain().getCodeSource().getLocation().getPath();

    private static Set<RapidNodeRunner> rapidNodeRunners;

    AbstractMultiJVMTest() {
        rapidNodeRunners = new HashSet<>();
    }

    @Getter
    private static File OUTPUT_LOG_DIR;

    @Before
    public void setUp() {
        OUTPUT_LOG_DIR = Files.createTempDir();
        OUTPUT_LOG_DIR.deleteOnExit();
    }

    /**
     * Deletes temp output directory.
     * Cleans up all rapidNodeRunners.
     */
    @After
    public void cleanUp() {
        // remove if kill successful
        rapidNodeRunners.removeIf(rapidNodeRunner -> {
            if (rapidNodeRunner.killOnExit) {
                if (!rapidNodeRunner.isKilled) {
                    try {
                        return rapidNodeRunner.killNode();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Cleanup all runners.
     */
    @AfterClass
    public static void cleanUpEnv() {
        rapidNodeRunners.forEach(rapidNodeRunner -> {
            if (!rapidNodeRunner.isKilled) {
                try {
                    rapidNodeRunner.killNode();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * RapidNodeRunner
     * To manage and run rapid processes.
     */
    @Accessors(chain = true)
    class RapidNodeRunner {

        private final String seed;
        private final String listenAddress;
        private final String role;
        private final String clusterName;

        @Getter
        private Process rapidProcess;

        private boolean isKilled = false;
        private boolean killOnExit = false;

        RapidNodeRunner(String seed, String listenAddress, String role, String clusterName) {
            this.seed = seed;
            this.listenAddress = listenAddress;
            this.role = role;
            this.clusterName = clusterName;
        }

        private class OutputLogger implements Runnable {
            private InputStream inputStream;
            private String logfile;

            private OutputLogger(InputStream inputStream, String logfile) {
                this.inputStream = inputStream;
                this.logfile = logfile;
            }

            @Override
            public void run() {
                new BufferedReader(new InputStreamReader(inputStream)).lines()
                        .forEach((x) -> {
                                    try {
                                        write(Paths.get(logfile), x.getBytes());
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                        );
            }
        }

        /**
         * Runs the rapid process with the provided parameters.
         *
         * @return RapidNodeRunner
         * @throws IOException if jar or temp directory is not found.
         */
        RapidNodeRunner runNode() throws IOException {

            File rapidRunnerJar = new File(RAPID_RUNNER_JAR);
            if (!rapidRunnerJar.exists()) {
                throw new FileNotFoundException("Rapid runner jar not found.");
            }
            String command = "java" +
                    " -server" +
                    " -jar " + RAPID_RUNNER_JAR +
                    " --listenAddress " + this.listenAddress +
                    " --seedAddress " + this.seed +
                    " --role " + this.role +
                    " --cluster " + this.clusterName;

            File outputLogFile = new File(OUTPUT_LOG_DIR + File.separator + UUID.randomUUID().toString());
            System.out.println("Output for listenAddress:" + listenAddress + " logged : " + outputLogFile.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c", command);
            Process rapidProcess = builder.start();
            OutputLogger outputLogger = new OutputLogger(rapidProcess.getInputStream(), outputLogFile.getAbsolutePath());
            Executors.newSingleThreadExecutor().submit(outputLogger);
            this.rapidProcess = rapidProcess;
            isKilled = false;
            rapidNodeRunners.add(this);

            return this;
        }

        /**
         * Kills the process on test exit.
         *
         * @return
         */
        RapidNodeRunner killOnExit() {
            killOnExit = true;
            return this;
        }

        /**
         * Kills the process.
         *
         * @return true if kill successful else false.
         * @throws IOException          if I/O error occurs.
         * @throws InterruptedException if kill interrupted.
         */
        boolean killNode() throws IOException {

            // Interval to wait after shutdown retry
            Long SHUTDOWN_RETRY_WAIT = 500L;
            // Number of retries to kill node before giving up.
            int SHUTDOWN_RETRIES = 10;
            // Timeout for a shutdown (millis)
            int SHUTDOWN_TIMEOUT = 5000;

            Assert.assertNotNull(rapidProcess);
            long retries = SHUTDOWN_RETRIES;

            while (true) {

                try {
                    this.rapidProcess.destroyForcibly().waitFor(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (retries == 0) {
                    return false;
                }

                if (rapidProcess.isAlive()) {
                    retries--;
                    try {
                        Thread.sleep(SHUTDOWN_RETRY_WAIT);
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    isKilled = true;
                    return true;
                }
            }
        }
    }
}
