import java.io.*;
import java.nio.file.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

record BankInfo(String name, String branch) {}
enum MenuOption { OPEN_ACCOUNT, DEPOSIT, WITHDRAW, TRANSFER, EXIT }
enum TransactionType { DEPOSIT, WITHDRAW, TRANSFER }
record Command(TransactionType type, String accountNumber, long amount) {}

interface Transactable {
    void deposit(long amount) throws InvalidAmountException;
    void withdraw(long amount) throws BankException;
}
interface InterestBearing {
    double interestRate();
    long getBalance();
    default double yearlyInterest() { return getBalance() * interestRate() / 100; }
}
@FunctionalInterface
interface WithdrawRule { boolean allow(Account account, long amount); }
interface Premium {}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Id {}
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Positive { String message() default "must be > 0"; }
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface MaxLength { int value(); }

class BankException extends Exception { BankException(String message) { super(message); } }
class InvalidAmountException extends BankException { InvalidAmountException() { super("Amount must be positive"); } }
class AccountNotFoundException extends BankException { AccountNotFoundException() { super("Account not found"); } }
class InsufficientFundsException extends BankException {
    private final long shortfall;
    InsufficientFundsException(long shortfall) {
        super("Not enough balance. Shortfall: " + shortfall);
        this.shortfall = shortfall;
    }
    long getShortfall() { return shortfall; }
}

class Customer implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;
    private static long customerCounter = 100;
    private final String customerId;
    @MaxLength(40)
    private String name;
    private String email, mobile;
    private Address address;
    private static String generateCustomerId() { return "CUST" + (++customerCounter); }
    Customer(String name, String email, String mobile, Address address) {
        customerId = generateCustomerId();
        this.name = name;
        this.email = email;
        this.mobile = mobile;
        this.address = address;
    }
    String getCustomerId() { return customerId; }
    String getName() { return name; }
    String getEmail() { return email; }
    String getMobile() { return mobile; }
    Address getAddress() { return address; }
    @Override public Customer clone() {
        try {
            Customer copy = (Customer) super.clone();
            if (address != null) copy.address = new Address(address.line, address.city, address.pincode);
            return copy;
        } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }
    public static class Address implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String line, city, pincode;
        Address(String line, String city, String pincode) {
            this.line = line; this.city = city; this.pincode = pincode;
        }
        String getLine() { return line; }
        String getCity() { return city; }
        String getPincode() { return pincode; }
    }
}

abstract class Account implements Transactable, InterestBearing, Comparable<Account>, Serializable {
    private static final long serialVersionUID = 1L;
    private static long nextNumber = 0;
    @Id private final String accountNumber;
    private final String ownerName;
    @Positive private long balance;
    private boolean active = true;

    Account(String ownerName, long openingBalance) {
        accountNumber = String.format("AC%04d", ++nextNumber);
        this.ownerName = ownerName;
        this.balance = openingBalance;
    }
    Account(String ownerName) { this(ownerName, 0); }
    static void updateCounter(Account[] accounts) {
        for (Account a : accounts) {
            try { nextNumber = Math.max(nextNumber, Long.parseLong(a.accountNumber.substring(2))); }
            catch (NumberFormatException e) { /* ignore malformed saved account */ }
        }
    }
    String getAccountNumber() { return accountNumber; }
    String getOwnerName() { return ownerName; }
    @Override public synchronized long getBalance() { return balance; }
    boolean isActive() { return active; }
    abstract boolean canWithdraw(long amount);
    @Override public synchronized void deposit(long amount) throws InvalidAmountException {
        if (amount <= 0) throw new InvalidAmountException();
        balance += amount;
    }
    @Override public synchronized void withdraw(long amount) throws BankException {
        if (amount <= 0) throw new InvalidAmountException();
        if (!canWithdraw(amount)) throw new InsufficientFundsException(Math.max(0, amount - balance));
        balance -= amount;
    }
 
    void transfer(Account to, long amount) throws BankException {
        if (to == null) throw new AccountNotFoundException();
        if (this == to) throw new BankException("Choose two different accounts");
        Account first = accountNumber.compareTo(to.accountNumber) < 0 ? this : to;
        Account second = first == this ? to : this;
        synchronized (first) {
            synchronized (second) {
                if (amount <= 0) throw new InvalidAmountException();
                if (to.balance > Long.MAX_VALUE - amount) throw new BankException("Receiver balance overflow");
                withdraw(amount);
                try { to.deposit(amount); }
                catch (InvalidAmountException e) { deposit(amount); throw e; }
            }
        }
    }
    @Override public String toString() {
        return accountNumber + " | " + ownerName + " | Rs." + getBalance() + " | " + getClass().getSimpleName();
    }
    @Override public boolean equals(Object o) {
        return o instanceof Account a && accountNumber.equals(a.accountNumber);
    }
    @Override public int hashCode() { return accountNumber.hashCode(); }
    @Override public int compareTo(Account other) { return accountNumber.compareTo(other.accountNumber); }
}
class SavingsAccount extends Account {
    private static final long serialVersionUID = 1L;
    private final long minBalance;
    SavingsAccount(String name, long balance, long minBalance) { super(name, balance); this.minBalance = minBalance; }
    @Override public double interestRate() { return 4.0; }
    @Override public boolean canWithdraw(long amount) { return getBalance() - amount >= minBalance; }
}
class CurrentAccount extends Account implements Premium {
    private static final long serialVersionUID = 1L;
    private final long overdraftLimit;
    CurrentAccount(String name, long balance, long overdraftLimit) { super(name, balance); this.overdraftLimit = overdraftLimit; }
    @Override public double interestRate() { return 0.0; }
    @Override public boolean canWithdraw(long amount) { return getBalance() - amount >= -overdraftLimit; }
}
class FixedDepositAccount extends Account {
    private static final long serialVersionUID = 1L;
    FixedDepositAccount(String name, long balance) { super(name, balance); }
    @Override public double interestRate() { return 7.0; }
    @Override public boolean canWithdraw(long amount) { return false; }
}

class Validator {
    private static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    static boolean isValidMobile(String s) { return s != null && MOBILE.matcher(s).matches(); }
    static boolean isValidEmail(String s) { return s != null && EMAIL.matcher(s).matches(); }
    static boolean isValidPan(String s) { return s != null && PAN.matcher(s).matches(); }
    static boolean isValidIfsc(String s) { return s != null && IFSC.matcher(s).matches(); }
}
class CommandParser {
    static Command parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3) throw new IllegalArgumentException("Example: DEPOSIT AC0001 500");
        return new Command(TransactionType.valueOf(parts[0].toUpperCase()), parts[1], Long.parseLong(parts[2]));
    }
}
class StatementFormatter {
    static String buildStatement(Account a) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ACCOUNT STATEMENT ---\n");
        sb.append("Account: ").append(a.getAccountNumber()).append("\n");
        sb.append("Name: ").append(a.getOwnerName()).append("\n");
        sb.append("Balance: Rs.").append(a.getBalance()).append("\n");
        return sb.toString();
    }
}
class AnnotationValidator {
    static String[] validate(Object obj) {
        ArrayList<String> errors = new ArrayList<>();
        for (Field field : obj.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                if (field.isAnnotationPresent(Positive.class) && value instanceof Number n && n.longValue() <= 0)
                    errors.add(field.getName() + " " + field.getAnnotation(Positive.class).message());
                if (field.isAnnotationPresent(MaxLength.class) && value instanceof String s && s.length() > field.getAnnotation(MaxLength.class).value())
                    errors.add(field.getName() + " too long");
            } catch (IllegalAccessException e) { errors.add(e.getMessage()); }
        }
        return errors.toArray(new String[0]);
    }
}

class Repository<T> {
    private final HashMap<String, T> data = new HashMap<>();
    T save(T item) {
        for (Field field : item.getClass().getSuperclass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                try { data.put(String.valueOf(field.get(item)), item); return item; }
                catch (IllegalAccessException e) { throw new IllegalArgumentException(e); }
            }
        }
        throw new IllegalArgumentException("No @Id field found");
    }
    T findById(String id) { return data.get(id); }
    ArrayList<T> findAll() { return new ArrayList<>(data.values()); }
    boolean delete(String id) { return data.remove(id) != null; }
    @SafeVarargs final void saveAll(T... items) { for (T item : items) save(item); }
    static <N extends Number> double average(N[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (N n : values) sum += n.doubleValue();
        return sum / values.length;
    }
}

class AccountStore {
    private final HashMap<String, Account> accounts = new HashMap<>();
    private final ArrayList<String> history = new ArrayList<>();
    void add(Account a) { accounts.put(a.getAccountNumber(), a); }
    Account get(String id) { return accounts.get(id); }
    ArrayList<Account> getAll() { return new ArrayList<>(accounts.values()); }
    void log(String s) { history.add(s); }
    ArrayList<String> getHistory() { return history; }
    ArrayList<Account> sortedByBalance() {
        ArrayList<Account> list = getAll();
        list.sort(Comparator.comparingLong(Account::getBalance));
        return list;
    }
}

class AccountWorker implements Runnable {
    private final Account account;
    private final int times;
    private final long amount;
    AccountWorker(Account a, int times, long amount) { account = a; this.times = times; this.amount = amount; }
    @Override public void run() {
        for (int i = 0; i < times; i++) {
            try { account.deposit(amount); }
            catch (InvalidAmountException e) { System.out.println(e.getMessage()); }
        }
        System.out.println(Thread.currentThread().getName() + " finished");
    }
}
class TransactionProcessor {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, Account> sharedAccounts = new ConcurrentHashMap<>();
    void add(Account a) { sharedAccounts.put(a.getAccountNumber(), a); }
    void submit(Runnable task) { pool.execute(task); }
    void stop() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
    }
}
class SimpleBuffer {
    private String item;
    synchronized void put(String text) throws InterruptedException {
        while (item != null) wait();
        item = text;
        notifyAll();
    }
    synchronized String take() throws InterruptedException {
        while (item == null) wait();
        String result = item;
        item = null;
        notifyAll();
        return result;
    }
}

class StatePersister {
    static void save(Account[] accounts, Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(accounts); }
    }
    static Account[] load(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) { return (Account[]) in.readObject(); }
    }
}
class TransactionLog {
    static void append(Path file, String line) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
class ReportGenerator {
    static String generate(Path folder, Path report) throws IOException {
        long deposits = 0, withdrawals = 0;
        Files.createDirectories(folder);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.log")) {
            for (Path file : files) {
                // Also read file attributes (size and modification time).
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(" ");
                        if (parts.length == 3) {
                            try {
                                if (parts[0].equals("DEPOSIT")) deposits += Long.parseLong(parts[2]);
                                if (parts[0].equals("WITHDRAW")) withdrawals += Long.parseLong(parts[2]);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        String text = "Deposits: Rs." + deposits + "\nWithdrawals: Rs." + withdrawals;
        if (report.getParent() != null) Files.createDirectories(report.getParent());
        Files.writeString(report, text);
        return text;
    }
}
class SmallResource implements AutoCloseable {
    SmallResource() { System.out.println("Resource opened"); }
    @Override public void close() { System.out.println("Resource closed"); }
}

class BankService {
    private final Repository<Account> repository = new Repository<>();
    private final AccountStore store = new AccountStore();
    private final Path logFile = Paths.get("logs", "bank.log");
    Account openAccount(String name, String type, long amount) throws BankException {
        if (name.isBlank()) throw new BankException("Name cannot be empty");
        if (amount < 0) throw new InvalidAmountException();
        Account a = switch (type.toUpperCase()) {
            case "S" -> new SavingsAccount(name, amount, 0);
            case "C" -> new CurrentAccount(name, amount, 1000);
            case "F" -> new FixedDepositAccount(name, amount);
            default -> throw new BankException("Choose S, C or F");
        };
        repository.save(a);
        store.add(a);
        return a;
    }
    Account get(String id) throws AccountNotFoundException {
        Account a = repository.findById(id);
        if (a == null) throw new AccountNotFoundException();
        return a;
    }
    void deposit(String id, long amount) throws BankException, IOException {
        Account a = get(id);
        a.deposit(amount);
        TransactionLog.append(logFile, "DEPOSIT " + id + " " + amount);
        store.log("Deposit " + id + " " + amount);
    }
    void withdraw(String id, long amount) throws BankException, IOException {
        Account a = get(id);
        a.withdraw(amount);
        TransactionLog.append(logFile, "WITHDRAW " + id + " " + amount);
        store.log("Withdraw " + id + " " + amount);
    }
    void transfer(String from, String to, long amount) throws BankException {
        get(from).transfer(get(to), amount);
        store.log("Transfer " + from + " to " + to + " " + amount);
    }
    String statement(String id) throws BankException { return StatementFormatter.buildStatement(get(id)); }
    ArrayList<Account> listAccounts() { return store.sortedByBalance(); }
    Account[] snapshot() { return store.getAll().toArray(new Account[0]); }
    void restore(Account[] saved) { Account.updateCounter(saved); for (Account a : saved) { repository.save(a); store.add(a); } }
    void batchDemo() throws Exception {
        Account[] accounts = snapshot();
        if (accounts.length == 0) { System.out.println("Create an account first"); return; }
        Account a = accounts[0];
        TransactionProcessor processor = new TransactionProcessor();
        processor.add(a);
        for (int i = 0; i < 10; i++) processor.submit(new AccountWorker(a, 100, 1));
        processor.stop();
        System.out.println("After 1000 deposits: " + a.getBalance());
    }
}

public class MiniBank {
    private static final Scanner sc = new Scanner(System.in);
    private static final Path SAVE_FILE = Paths.get("data", "accounts.dat");
    private static String ask(String prompt) { System.out.print(prompt); return sc.nextLine().trim(); }
    private static long askAmount(String prompt) { return Long.parseLong(ask(prompt)); }

    public static void main(String[] args) {
        BankInfo info = new BankInfo("MiniBank", "Anand");
        BankService bank = new BankService();
        if (Files.exists(SAVE_FILE)) {
            try { bank.restore(StatePersister.load(SAVE_FILE)); System.out.println("Saved accounts loaded"); }
            catch (Exception e) { System.out.println("Load error: " + e.getMessage()); }
        }
        System.out.println("Welcome to " + info.name() + " - " + info.branch());
        while (true) {
            System.out.println("\n1. Open Account\n2. Deposit\n3. Withdraw\n4. Transfer");
            System.out.println("5. Statement\n6. All Accounts\n7. Report\n8. Thread Demo\n9. Concept Demo\n0. Exit");
            String choice = ask("Choice: ");
            if (choice.equals("0")) break;
            try {
                switch (choice) {
                    case "1" -> {
                        String name = ask("Name: ");
                        String type = ask("Type (S=Savings, C=Current, F=FD): ");
                        long opening = askAmount("Opening balance: ");
                        System.out.println("Created: " + bank.openAccount(name, type, opening));
                    }
                    case "2" -> { bank.deposit(ask("Account no: "), askAmount("Amount: ")); System.out.println("Deposited"); }
                    case "3" -> { bank.withdraw(ask("Account no: "), askAmount("Amount: ")); System.out.println("Withdrawn"); }
                    case "4" -> {
                        String from = ask("From account: ");
                        String to = ask("To account: ");
                        bank.transfer(from, to, askAmount("Amount: "));
                        System.out.println("Transferred");
                    }
                    case "5" -> System.out.println(bank.statement(ask("Account no: ")));
                    case "6" -> bank.listAccounts().forEach(System.out::println);
                    case "7" -> System.out.println(ReportGenerator.generate(Paths.get("logs"), Paths.get("reports", "summary.txt")));
                    case "8" -> bank.batchDemo();
                    case "9" -> conceptDemo();
                    default -> System.out.println("Invalid choice");
                }
            } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
        }
        try { StatePersister.save(bank.snapshot(), SAVE_FILE); System.out.println("Accounts saved. Goodbye!"); }
        catch (IOException e) { System.out.println("Save error: " + e.getMessage()); }
        sc.close();
    }

    private static void conceptDemo() throws Exception {
        System.out.println("Mobile valid: " + Validator.isValidMobile("9876543210"));
        System.out.println("Email valid: " + Validator.isValidEmail("abc@gmail.com"));
        System.out.println("PAN valid: " + Validator.isValidPan("ABCDE1234F"));
        System.out.println("IFSC valid: " + Validator.isValidIfsc("SBIN0001234"));
        Command c = CommandParser.parse("DEPOSIT AC0001 500");
        System.out.println("Command: " + c.type() + " " + c.accountNumber() + " " + c.amount());
        Account[] sample = { new SavingsAccount("A", 1000, 100), new CurrentAccount("B", 1000, 500), new FixedDepositAccount("C", 1000) };
        for (Account a : sample) System.out.println(a + " | Interest: " + a.yearlyInterest());
        System.out.println("Equals: " + sample[0].equals(sample[1]));
        System.out.println("instanceof Savings: " + (sample[0] instanceof SavingsAccount));
        WithdrawRule anonymous = new WithdrawRule() { public boolean allow(Account a, long amount) { return a.canWithdraw(amount); } };
        WithdrawRule lambda = (a, amount) -> a.canWithdraw(amount);
        System.out.println("Anonymous rule: " + anonymous.allow(sample[0], 100));
        System.out.println("Lambda rule: " + lambda.allow(sample[0], 100));
        System.out.println("Annotations: " + Arrays.toString(AnnotationValidator.validate(new SavingsAccount("Bad", -10, 0))));
        Customer customer = new Customer("Ravi", "ravi@gmail.com", "9876543210", new Customer.Address("Road 1", "Anand", "388001"));
        System.out.println("Cloned customer: " + customer.clone().getCustomerId());
        Repository<Account> repo = new Repository<>();
        repo.saveAll(sample);
        System.out.println("Repository items: " + repo.findAll().size());
        System.out.println("Average: " + Repository.average(new Integer[] {10, 20, 30}));
        try (SmallResource r = new SmallResource()) { System.out.println("Using resource"); }
        SimpleBuffer buffer = new SimpleBuffer();
        Thread consumer = new Thread(() -> { try { System.out.println("Consumed: " + buffer.take()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        consumer.start();
        buffer.put("DEPOSIT AC0001 500");
        consumer.join();
    }
}
