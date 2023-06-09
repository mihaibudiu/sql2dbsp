/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 *
 */

package org.dbsp.sqllogictest;

import com.beust.jcommander.ParameterException;
import org.apache.calcite.sql.parser.SqlParseException;
import org.dbsp.sqlCompiler.compiler.backend.rust.RustSqlRuntimeLibrary;
import org.dbsp.sqllogictest.executors.*;
import org.dbsp.util.Linq;
import org.dbsp.util.TestStatistics;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Execute all SqlLogicTest tests.
 */
public class Main {
    static final String SLT_GIT = "https://github.com/gregrahn/sqllogictest/archive/refs/heads/master.zip";

    static class TestLoader extends SimpleFileVisitor<Path> {
        int errors = 0;
        final TestStatistics statistics;
        public final ExecutionOptions options;
        /**
         * This policy accepts all SLT queries and statements written in the Postgres SQL language.
         */
        static class PostgresPolicy implements AcceptancePolicy {
            @Override
            public boolean accept(List<String> skip, List<String> only) {
                if (only.contains("postgresql"))
                    return true;
                if (!only.isEmpty())
                    return false;
                return !skip.contains("postgresql");
            }
        }

        /**
         * Creates a new class that reads tests from a directory tree and executes them.
         */
        TestLoader(ExecutionOptions options) {
            this.statistics = new TestStatistics(options.stopAtFirstError);
            this.options = options;
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            SqlSLTTestExecutor executor = null;
            try {
                executor = this.options.getExecutor();
            } catch (IOException | SQLException e) {
                throw new RuntimeException(e);
            }
            String extension = Utilities.getFileExtension(file.toString());
            int batchSize = 500;
            int skipPerFile = 0;
            String name = file.getFileName().toString();
            if (name.startsWith("select"))
                batchSize = Math.min(batchSize, 20);
            if (name.startsWith("select5"))
                batchSize = Math.min(batchSize, 5);
            if (executor.is(DBSPExecutor.class))
                executor.to(DBSPExecutor.class).setBatchSize(batchSize, skipPerFile);
            if (attrs.isRegularFile() && extension != null && extension.equals("test")) {
                // validates the test
                SLTTestFile test = null;
                try {
                    System.out.println(file);
                    test = new SLTTestFile(file.toString());
                    test.parse(new PostgresPolicy());
                } catch (Exception ex) {
                    System.err.println(ex.toString());
                    this.errors++;
                }
                if (test != null) {
                    try {
                        TestStatistics stats = executor.execute(test, options);
                        this.statistics.add(stats);
                    } catch (SqlParseException | IOException | SQLException | NoSuchAlgorithmException |
                             InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    @Nullable
    static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        String name = zipEntry.getName();
        name = name.replace("sqllogictest-master/", "");
        if (name.isEmpty())
            return null;
        File destFile = new File(destinationDir, name);
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + name);
        }
        return destFile;
    }

    static void install(File directory) throws IOException {
        System.out.println("Downloading SLT from " + SLT_GIT);
        File zip = File.createTempFile("out", ".zip", new File("."));
        zip.deleteOnExit();
        InputStream in = new URL(SLT_GIT).openStream();
        Files.copy(in, zip.toPath(), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Unzipping data");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(directory, zipEntry);
                if (newFile != null) {
                    System.out.println("Creating " + newFile.getPath());
                    if (zipEntry.isDirectory()) {
                        if (!newFile.isDirectory() && !newFile.mkdirs()) {
                            throw new IOException("Failed to create directory " + newFile);
                        }
                    } else {
                        File parent = newFile.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException("Failed to create directory " + parent);
                        }

                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            int len;
                            byte[] buffer = new byte[1024];
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    static void abort(ExecutionOptions options, @Nullable String message) {
        if (message != null)
            System.err.println(message);
        options.usage();
        System.exit(1);
    }

    @SuppressWarnings("SpellCheckingInspection")
    public static void main(String[] argv) throws IOException {
        RustSqlRuntimeLibrary.INSTANCE.writeSqlLibrary( "../lib/genlib/src/lib.rs");
        List<String> files = Linq.list(
                "/index/random/1000/slt_good_0.test"
                /*
                "select1.test"
                "select2.test",
                "select3.test",
                "select4.test",
                "select5.test",
                "random/select",
                "random/aggregates",
                "random/groupby",
                "random/expr",
                "index/commute",
                "index/orderby",
                "index/between",
                "index/view/",
                "index/in",      
                "index/delete",  
                "index/commute", 
                "index/orderby_nosort", 
                "index/random",  
                "evidence"
                 */
        );

        String[] args = {
                "-e", "hybrid",        // executor
                "."
                //"-inc",              // incremental (streaming) testing
                //"-j"                 // Validate JSON IR.
        };
        if (argv.length > 0) {
            args = argv;
        } else {
            List<String> a = new ArrayList<>();
            a.addAll(Linq.list(args));
            a.addAll(files);
            args = a.toArray(new String[0]);
        }
        /*
        Logger.INSTANCE.setDebugLevel(JDBCExecutor.class, 3);
        Logger.INSTANCE.setDebugLevel(DBSP_JDBC_Executor.class, 3);
        Logger.INSTANCE.setDebugLevel(CalciteExecutor.class, 1);
        Logger.INSTANCE.setDebugLevel(DBSPExecutor.class, 3);
        Logger.INSTANCE.setDebugLevel(SLTTestFile.class, 3);
        Logger.INSTANCE.setDebugLevel(DBSPExecutor.class, 3);
        Logger.INSTANCE.setDebugLevel(CalciteCompiler.class, 3);
         */
        ExecutionOptions options = new ExecutionOptions();
        try {
            options.parse(args);
            System.out.println(options);
        } catch (ParameterException ex) {
            abort(options, null);
        }
        if (options.help)
            abort(options, null);
        if (options.sltDirectory == null)
            abort(options, "Please specify the directory with the SqlLogicTest suite using the -d flag");

        File dir = new File(options.sltDirectory);
        if (dir.exists()) {
            if (!dir.isDirectory())
                abort(options, options.sltDirectory + " is not a directory");
            if (options.install)
                System.err.println("Directory " + options.sltDirectory + " exists; skipping download");
        } else {
            if (options.install) {
                install(dir);
            } else {
                abort(options, options.sltDirectory + " does not exist and no installation was specified");
            }
        }
        TestLoader loader = new TestLoader(options);
        for (String file : options.getDirectories()) {
            Path path = Paths.get(options.sltDirectory + "/test/" + file);
            Files.walkFileTree(path, loader);
        }
        System.out.println("Files that could not be not parsed: " + loader.errors);
        System.out.println(loader.statistics);
    }
}
