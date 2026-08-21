import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import junit.framework.TestCase;

public class ConnectionTests extends TestCase {
    static final String JDBC_DRIVER = "dk.biering.jdbc.IamWrapper";
    static final String JDBC_HOST = System.getenv("JDBC_HOST");
    static final String JDBC_USER = System.getenv("JDBC_USER");
    static final String JDBC_PASSWORD = System.getenv("JDBC_PASSWORD");

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        try {
            // The newInstance() call is a workaround for some
            // broken Java implementations

            Class.forName(JDBC_DRIVER).getConstructor().newInstance();
        } catch (Exception ex) {
            // handle the error
        }
    }

    public void testConnectWrapper() throws SQLException {
        Properties info = new Properties();
        info.put("user", JDBC_USER);
        info.put("password", JDBC_PASSWORD);

        System.out.println("Connecting to a selected database...");
        var _ =
                DriverManager.getConnection(
                        "jdbc:iam:mysql://" + JDBC_HOST + "/information_schema?hello=yes", info);
        System.out.println("Connected successfully to database");
    }

    public void testConnectWrapper2() throws SQLException {
        System.out.println("Connecting to a selected database...");
        var _ =
                DriverManager.getConnection(
                        "jdbc:iam:mysql://%s/information_schema?user=%s&password=%s&hello=yes"
                                .formatted(JDBC_HOST, JDBC_USER, JDBC_PASSWORD));
        System.out.println("Connected successfully to database");
    }

    public void testConnectWrapper3() throws SQLException {
        System.out.println("Connecting to a selected database...");
        var _ =
                DriverManager.getConnection(
                        "jdbc:iam:mysql://%s:%s@%s/information_schema?hello=yes"
                                .formatted(JDBC_USER, JDBC_PASSWORD, JDBC_HOST));
        System.out.println("Connected successfully to database");
    }
}
