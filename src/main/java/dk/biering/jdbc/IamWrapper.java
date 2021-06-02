package dk.biering.jdbc;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

public class IamWrapper implements java.sql.Driver {
    private static final Logger LOGGER = Logger.getLogger(IamWrapper.class.getName());

    private static final Integer DEFAULT_PORT_MYSQL = 3306;
    private static final String DEFAULT_DRIVER_MYSQL = "com.mysql.cj.jdbc.Driver";
    private static final String DEFAULT_DRIVER_MARIADB = "org.mariadb.jdbc.Driver";

    private static final String JDBC_URL_PREFIX = "jdbc:";
    private static final String JDBC_IAM_PREFIX = "iam:";

    public static final String AWS_REGION_PROPERTY = "awsRegion";
    public static final String AWS_REGION_FROM_HOST_REGEX =
            "\\.([a-z0-9-]+)\\.rds\\.amazonaws\\.com$";
    public static final String LOAD_JDBC_DRIVER_CLASS_PROPERTY = "loadJdbcDriverClass";

    private Driver delegate;

    //
    // Register ourselves with the DriverManager
    //
    static {
        try {
            LOGGER.info("Registering IAM wrapper");
            java.sql.DriverManager.registerDriver(new IamWrapper());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error registering IAM wrapper", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Construct a new driver and register it with DriverManager
     *
     * @throws SQLException if a database error occurs.
     */
    public IamWrapper() throws SQLException {
        super();
    }

    public boolean acceptsURL(String url) throws SQLException {
        assertUrlNotNull(url);
        return url.startsWith(JDBC_URL_PREFIX + JDBC_IAM_PREFIX);
    }

    public Connection connect(String url, Properties connectionProperties) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        url = stripIamPrefixFromUrl(url);
        String userInfo = extractUserInfoFromUrl(url);
        url = removeUserInfoFromUrl(url);

        URI parsedUrl = parseJdbcUrl(url);

        Map<String, String> properties =
                mergeProperties(
                        connectionProperties, parseQueryString(parsedUrl), parseUserInfo(userInfo));

        resolveDelegateDriver(url, properties);

        String user = properties.get("user");
        if (user == null) {
            throw new SQLException(
                    "User couldn't be automatically determined. Please define `user` in query string or property.");
        }

        String awsProfile = properties.get("password");
        if (awsProfile == null) {
            throw new SQLException(
                    "Password/AWS Profile isn't specified. Please define the AWS Profile as `password` in query string or property.");
        }

        String host = getHost(parsedUrl);
        Integer port = getPort(parsedUrl);
        String awsRegion = getAwsRegion(properties, host, awsProfile);

        connectionProperties.put("user", user);
        connectionProperties.put("useSSL", "true");
        connectionProperties.put("requireSSL", "true");

        if (!properties.containsKey("enabledTLSProtocols")) {
            connectionProperties.put("enabledTLSProtocols", "TLSv1.2,TLSv1.3");
        }

        if (!properties.containsKey("verifyServerCertificate")) {
            connectionProperties.put("verifyServerCertificate", "true");
        }

        if (!properties.containsKey("trustCertificateKeyStoreUrl")) {
            URL jksPath = IamWrapper.class.getClassLoader().getResource("rds-ca-2019-root.pem.jks");
            if (jksPath != null) {
                connectionProperties.put("trustCertificateKeyStoreUrl", jksPath.toString());
                connectionProperties.put("trustCertificateKeyStorePassword", "changeme");
            } else {
                LOGGER.info("Unable to find the embedded trustCertificateKeyStore");
                throw new SQLException("Unable to find the embedded trustCertificateKeyStore");
            }
        }

        try {
            LOGGER.info(
                    "Generating RDS IAM auth token for: "
                            + "AwsProfile="
                            + awsProfile
                            + ", AwsRegion="
                            + awsRegion
                            + ", Host="
                            + host
                            + ", Port="
                            + port
                            + ", User="
                            + user);

            String authToken = generateAuthToken(awsProfile, host, port, user, awsRegion);
            connectionProperties.put("password", authToken);
        } catch (Exception e) {
            LOGGER.info("generateAuthToken exception: " + e.getMessage());
            throw new SQLException(e.getMessage(), e);
        }

        Properties loggingProperties = (Properties) connectionProperties.clone();
        loggingProperties.put("password", "hidden-from-log");

        LOGGER.info("Connecting with url: " + url + " and properties: " + loggingProperties);

        return delegate.connect(url, connectionProperties);
    }

    private void resolveDelegateDriver(String delegatedUrl, Map<String, String> properties)
            throws SQLException {
        if (delegate != null) {
            return;
        }

        String driverToResolve = properties.get(LOAD_JDBC_DRIVER_CLASS_PROPERTY);

        if (driverToResolve == null) {
            URI parsedUrl = parseJdbcUrl(delegatedUrl);

            if (parsedUrl.getScheme().equals("mysql")) {
                driverToResolve = DEFAULT_DRIVER_MYSQL;
            } else if (parsedUrl.getScheme().equals("mariadb")) {
                driverToResolve = DEFAULT_DRIVER_MARIADB;
            } else {
                throw new SQLException(
                        "Driver couldn't be automatically determined. Please define `"
                                + LOAD_JDBC_DRIVER_CLASS_PROPERTY
                                + "` in query string or property.");
            }
        }

        try {
            LOGGER.info("Try resolving driver: " + driverToResolve);
            delegate = resolveDriver(driverToResolve);
        } catch (Exception e) {
            throw new SQLException("Unable to load delegate JDBC driver", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Driver resolveDriver(String driverClassName)
            throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
                    InvocationTargetException, InstantiationException {
        Class<Driver> driverClass = (Class<Driver>) Class.forName(driverClassName);
        return driverClass.getDeclaredConstructor().newInstance();
    }

    private String removeUserInfoFromUrl(String url) {
        return url.replaceFirst("//.*?@", "//");
    }

    private String stripIamPrefixFromUrl(String url) {
        return JDBC_URL_PREFIX + url.substring((JDBC_URL_PREFIX + JDBC_IAM_PREFIX).length());
    }

    private String extractUserInfoFromUrl(String url) {
        return parseJdbcUrl(url).getUserInfo();
    }

    private String getAwsRegion(Map<String, String> properties, String host, String awsProfile)
            throws SQLException {
        if (properties.get(AWS_REGION_PROPERTY) != null) {
            return properties.get(AWS_REGION_PROPERTY);
        }

        Matcher matcher = Pattern.compile(AWS_REGION_FROM_HOST_REGEX).matcher(host);
        if (matcher.find()) {
            return matcher.group(1);
        }

        try {
            AwsRegionProvider regionProvider =
                    DefaultAwsRegionProviderChain.builder().profileName(awsProfile).build();
            return regionProvider.getRegion().id();
        } catch (SdkClientException e) {
        }

        throw new SQLException(
                "AWS Region couldn't be automatically determined. Please define `"
                        + AWS_REGION_PROPERTY
                        + "` in query string or property, or set default region in the AWS Profile.");
    }

    private String getHost(URI parsedUrl) throws SQLException {
        if (parsedUrl.getHost() != null) {
            return parsedUrl.getHost();
        } else {
            throw new SQLException(
                    "No database host specified. IAM Auth requires that a host be specified in the JDBC URL.");
        }
    }

    private int getPort(URI parsedUrl) throws SQLException {
        if (parsedUrl.getPort() != -1) {
            return parsedUrl.getPort();
        } else if (parsedUrl.getScheme().equals("mysql")) {
            return DEFAULT_PORT_MYSQL;
        } else if (parsedUrl.getScheme().equals("mariadb")) {
            return DEFAULT_PORT_MYSQL;
        } else {
            throw new SQLException(
                    "No database port specified. IAM Auth requires that either a default port be pre-configured or a port is specified in the JDBC URL.");
        }
    }

    private URI parseJdbcUrl(String url) {
        if (url == null || !url.startsWith(JDBC_URL_PREFIX)) {
            return null;
        }
        String substring = url.substring(JDBC_URL_PREFIX.length());
        return URI.create(substring);
    }

    protected String generateAuthToken(
            String awsProfile, String host, Integer port, String user, String awsRegion) {

        ProfileCredentialsProvider provider =
                ProfileCredentialsProvider.builder().profileName(awsProfile).build();

        AwsCredentials credentials = provider.resolveCredentials();

        Aws4PresignerParams params =
                Aws4PresignerParams.builder()
                        .expirationTime(Instant.now().plusSeconds(15 * 60))
                        .awsCredentials(credentials)
                        .signingName("rds-db")
                        .signingRegion(Region.of(awsRegion))
                        .build();

        SdkHttpFullRequest request =
                SdkHttpFullRequest.builder()
                        .encodedPath("/")
                        .host(host)
                        .port(port)
                        .protocol("http") // Will be stripped off; but we need to satisfy
                        // SdkHttpFullRequest
                        .method(SdkHttpMethod.GET)
                        .appendRawQueryParameter("Action", "connect")
                        .appendRawQueryParameter("DBUser", user)
                        .build();

        String fullUrl = Aws4Signer.create().presign(request, params).getUri().toString();

        return fullUrl.substring(7); // remove prefix http://
    }

    private static Map<String, String> mergeProperties(
            Properties properties,
            Map<String, String> uriProperties,
            Map<String, String> userInfoProperties) {
        Map<String, String> merged = new HashMap<>();
        properties.stringPropertyNames().forEach(sp -> merged.put(sp, properties.getProperty(sp)));
        // URI properties take precedence over connection properties.
        // This is in-line with the behavior of JDBC drivers like postgres
        // It also makes sense, since we use URI properties are used in certain situations to
        // resolve the driver, before connection properties are available
        merged.putAll(uriProperties);
        // UserInfo properties take precedence over connection and URI properties.
        merged.putAll(userInfoProperties);
        return merged;
    }

    private static Map<String, String> parseQueryString(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> queryParams = new LinkedHashMap<>();
        String query = uri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx < 0) {
                continue;
            }
            queryParams.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
        }
        return queryParams;
    }

    private static Map<String, String> parseUserInfo(String userInfo) {
        if (userInfo == null || userInfo.isEmpty()) {
            return Collections.emptyMap();
        }

        String[] parts = userInfo.split(":");
        if (parts.length != 2) {
            return Collections.emptyMap();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("user", parts[0]);
        properties.put("password", parts[1]);

        return properties;
    }

    /**
     * {@link URLDecoder} is designed for decoding {@code application/x-www-form-urlencoded} URL's,
     * specifically it decodes {@code +} as space.
     *
     * <p>Form url encoding is an extension to standard RFC3986, which is not applicable for this
     * use case.
     *
     * <p>Encoding of {@code +} to space is undesirable, as AWS secret access keys may contain
     * {@code +}, and would require encoding to be correctly parsed.
     */
    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getMajorVersion() {
        if (delegate == null) {
            logDelegateNotInitialised("getMajorValue");
            return -1;
        }
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        if (delegate == null) {
            logDelegateNotInitialised("getMinorVersion");
            return -1;
        }
        return delegate.getMinorVersion();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        if (delegate == null) {
            logDelegateNotInitialised("getParentLogger");
            throw new SQLFeatureNotSupportedException("Delegate driver not initialised");
        }
        return delegate.getParentLogger();
    }

    private void attemptDelegateDriverResolve(String url, Map<String, String> properties) {
        try {
            resolveDelegateDriver(url, properties);
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "Attempt to resolve delegate driver failed", e);
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties connectionProperties)
            throws SQLException {
        assertUrlNotNull(url);

        url = stripIamPrefixFromUrl(url);
        String userInfo = extractUserInfoFromUrl(url);
        url = removeUserInfoFromUrl(url);

        URI parsedUrl = parseJdbcUrl(url);

        Map<String, String> properties =
                mergeProperties(
                        connectionProperties, parseQueryString(parsedUrl), parseUserInfo(userInfo));

        attemptDelegateDriverResolve(url, properties);

        return delegate.getPropertyInfo(url, connectionProperties);
    }

    @Override
    public boolean jdbcCompliant() {
        if (delegate == null) {
            logDelegateNotInitialised("jdbcCompliant");
            return false;
        }
        return delegate.jdbcCompliant();
    }

    private void assertUrlNotNull(String url) throws SQLException {
        if (url == null) {
            throw new SQLException("URL is null", new NullPointerException());
        }
    }

    private void logDelegateNotInitialised(String method) {
        LOGGER.warning(
                "Method "
                        + method
                        + " called, but delegate driver not initialised, returning bogus value");
    }
}
