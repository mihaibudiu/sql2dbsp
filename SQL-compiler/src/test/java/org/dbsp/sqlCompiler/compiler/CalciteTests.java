package org.dbsp.sqlCompiler.compiler;

import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.validate.implicit.TypeCoercionImpl;
import org.dbsp.sqlCompiler.dbsp.CalciteToDBSPCompiler;
import org.dbsp.sqlCompiler.dbsp.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.frontend.CalciteCompiler;
import org.dbsp.sqlCompiler.frontend.CalciteProgram;
import org.dbsp.util.IndentStringBuilder;
import org.junit.Test;

import java.io.*;

public class CalciteTests {
    private CalciteCompiler compileDef() throws SqlParseException {
        CalciteCompiler calcite = new CalciteCompiler();
        String ddl = "CREATE TABLE T (\n" +
                "COL1 INT," +
                "COL2 FLOAT," +
                "COL3 BOOLEAN" +
                //"COL4 VARCHAR" +
                ")";

        calcite.compile(ddl);
        return calcite;
    }

    static String rustDirectory = "../temp";
    static String testFilePath = rustDirectory + "/src/test.rs";

    private String compileQuery(String query) throws SqlParseException {
        CalciteCompiler calcite = this.compileDef();
        calcite.compile(query);
        CalciteProgram program = calcite.getProgram();

        CalciteToDBSPCompiler compiler = new CalciteToDBSPCompiler();
        DBSPCircuit dbsp = compiler.compile(program);
        IndentStringBuilder builder = new IndentStringBuilder();
        dbsp.toRustString(builder);
        return builder.toString();
    }

    private void writeToFile(String file, String contents) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(file, "UTF-8");
        writer.print(contents);
        writer.close();
    }

    private void compileRust(String directory) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("cargo", "build");
        processBuilder.directory(new File(directory));
        Process process = processBuilder.start();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
        int exitCode = process.waitFor();
        assert exitCode == 0;
    }

    private void testQuery(String query) {
        try {
            String rust = this.compileQuery(query);
            this.writeToFile(testFilePath, rust);
            this.compileRust(rustDirectory);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    public void projectTest() {
        String query = "CREATE VIEW V AS SELECT T.COL3 FROM T";
        this.testQuery(query);
    }

    @Test
    public void unionTest() {
        String query = "CREATE VIEW V AS (SELECT * FROM T) UNION (SELECT * FROM T)";
        this.testQuery(query);
    }

    @Test
    public void whereTest() {
        String query = "CREATE VIEW V AS SELECT * FROM T WHERE COL3";
        this.testQuery(query);
    }

    @Test
    public void whereImplicitCastTest() {
        String query = "CREATE VIEW V AS SELECT * FROM T WHERE COL2 < COL1";
        this.testQuery(query);
    }

    @Test
    public void whereExplicitCastTest() {
        String query = "CREATE VIEW V AS SELECT * FROM T WHERE COL2 < CAST(COL1 AS FLOAT)";
        this.testQuery(query);
    }

    @Test
    public void whereExpressionTest() {
        String query = "CREATE VIEW V AS SELECT * FROM T WHERE COL2 < 0";
        this.testQuery(query);
    }

    @Test
    public void exceptTest() {
        String query = "CREATE VIEW V AS SELECT * FROM T EXCEPT (SELECT * FROM T WHERE COL3)";
        this.testQuery(query);
    }
}
