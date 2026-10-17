import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public final class S08CrashRecoveryProbe {
    private static final String TABLE = System.getenv("S08_CRASH_TABLE");
    private static final String URL = System.getenv("S08_CRASH_DB_URL");
    private static final String USER = System.getenv("S08_CRASH_DB_USER");
    private static final String PASSWORD = System.getenv("S08_CRASH_DB_PASSWORD");

    private static Connection connect() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
        case "create-database" -> createDatabase();
        case "drop-database" -> dropDatabase();
        case "init" -> init();
        case "crash" -> crash();
        case "commit" -> commit();
        case "retry" -> retry();
        case "hold-account" -> holdAccount();
        case "account-state" -> accountState();
        case "read" -> read();
        case "cleanup" -> cleanup();
        default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static Connection admin() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(System.getenv("S08_CRASH_ADMIN_URL"), USER, PASSWORD);
    }

    private static void createDatabase() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + System.getenv("S08_CRASH_DB_NAME") + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void dropDatabase() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + System.getenv("S08_CRASH_DB_NAME") + "`");
        }
    }

    private static void init() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE `" + TABLE + "_account` (id INT PRIMARY KEY, balance BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE `" + TABLE + "_ledger` (id INT AUTO_INCREMENT PRIMARY KEY, business_reference VARCHAR(128) NOT NULL UNIQUE, points_delta BIGINT NOT NULL)");
            statement.executeUpdate("INSERT INTO `" + TABLE + "_account`(id,balance) VALUES(1,100)");
        }
    }

    private static void crash() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO `" + TABLE + "_ledger`(business_reference,points_delta) VALUES(?,?)");
                    PreparedStatement update = connection.prepareStatement("UPDATE `" + TABLE + "_account` SET balance=balance+? WHERE id=1")) {
                insert.setString(1, "crash-uncommitted");
                insert.setLong(2, -10);
                insert.executeUpdate();
                update.setLong(1, -10);
                update.executeUpdate();
                Runtime.getRuntime().halt(137);
            }
        }
    }

    private static void commit() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO `" + TABLE + "_ledger`(business_reference,points_delta) VALUES(?,?)");
                    PreparedStatement update = connection.prepareStatement("UPDATE `" + TABLE + "_account` SET balance=balance+? WHERE id=1")) {
                insert.setString(1, "crash-committed");
                insert.setLong(2, -10);
                insert.executeUpdate();
                update.setLong(1, -10);
                update.executeUpdate();
                connection.commit();
            }
        }
    }

    private static void retry() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            int inserted;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO `" + TABLE + "_ledger`(business_reference,points_delta) VALUES(?,?)")) {
                insert.setString(1, "crash-committed");
                insert.setLong(2, -10);
                try {
                    insert.executeUpdate();
                    inserted = 1;
                } catch (java.sql.SQLIntegrityConstraintViolationException duplicate) {
                    inserted = 0;
                }
            }
            if (inserted == 1) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE `" + TABLE + "_account` SET balance=balance+? WHERE id=1")) {
                    update.setLong(1, -10);
                    update.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    private static void read() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet account = statement.executeQuery("SELECT balance FROM `" + TABLE + "_account` WHERE id=1")) {
            account.next();
            try (PreparedStatement ledger = connection.prepareStatement("SELECT COUNT(*) FROM `" + TABLE + "_ledger` WHERE business_reference=?")) {
                ledger.setString(1, "crash-uncommitted");
                try (ResultSet rows = ledger.executeQuery()) {
                    rows.next();
                    System.out.println("S08CRASH|" + account.getLong(1) + "|" + rows.getInt(1));
                }
            }
        }
    }

    private static void holdAccount() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM points_account WHERE user_id=UUID_TO_BIN(?) FOR UPDATE")) {
                statement.setString(1, System.getenv("S08_CRASH_USER_ID"));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("points account not found");
                    }
                    System.out.println("S08_ACCOUNT_LOCK_READY");
                    System.out.flush();
                    while (true) {
                        Thread.sleep(1000);
                    }
                }
            }
        }
    }

    private static void accountState() throws Exception {
        try (Connection connection = connect();
                PreparedStatement balance = connection.prepareStatement(
                        "SELECT balance_points FROM points_account WHERE user_id=UUID_TO_BIN(?)");
                PreparedStatement ledger = connection.prepareStatement(
                        "SELECT COUNT(*) FROM points_ledger WHERE user_id=UUID_TO_BIN(?) AND entry_type='RESERVE'")) {
            balance.setString(1, System.getenv("S08_CRASH_USER_ID"));
            ledger.setString(1, System.getenv("S08_CRASH_USER_ID"));
            try (ResultSet balanceRows = balance.executeQuery(); ResultSet ledgerRows = ledger.executeQuery()) {
                balanceRows.next();
                ledgerRows.next();
                System.out.println("S08_APP_CRASH_STATE|" + balanceRows.getLong(1) + "|" + ledgerRows.getInt(1));
            }
        }
    }

    private static void cleanup() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS `" + TABLE + "_ledger`");
            statement.executeUpdate("DROP TABLE IF EXISTS `" + TABLE + "_account`");
        }
    }
}
