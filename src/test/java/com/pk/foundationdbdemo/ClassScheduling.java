package com.pk.foundationdbdemo;


import com.apple.foundationdb.*;
import com.apple.foundationdb.tuple.Tuple;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Stopwatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 *
 * from https://apple.github.io/foundationdb/class-scheduling-java.html
 */
// Data model:
// ("attends", student, class) = ""
// ("class", class_name) = seatsLeft
public class ClassScheduling {
    private static final MetricRegistry metrics = new MetricRegistry();
    private static ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build();

    private static final FDB fdb;
    private static final Database db;

    static {
        fdb = FDB.selectAPIVersion(510);
        db = fdb.open();
    }

    // Generate 1,620 classes like '9:00 chem for dummies'
    private static List<String> levels = Arrays.asList("intro", "for dummies",
            "remedial", "101", "201", "301", "mastery", "lab", "seminar");

    private static List<String> types = Arrays.asList("chem", "bio", "cs",
            "geometry", "calc", "alg", "film", "music", "art", "dance");

    private static List<String> times = Arrays.asList("2:00", "3:00", "4:00",
            "5:00", "6:00", "7:00", "8:00", "9:00", "10:00", "11:00", "12:00", "13:00",
            "14:00", "15:00", "16:00", "17:00", "18:00", "19:00");

    private static List<String> classNames = initClassNames();

    private static List<String> initClassNames() {
        List<String> classNames = new ArrayList<>();
        for (String level: levels)
            for (String type: types)
                for (String time: times)
                    classNames.add(time + " " + type + " " + level);
        return classNames;
    }

    /**
     * initializes a row with the given class with 100 available seats
     * @param c name of the class to initialize
     */
    private static void initSeats(TransactionContext db, final String c) {
        db.run((Transaction tr) -> {
            tr.set(Tuple.from("class", c).pack(), encodeInt(100));
            return null;
        });
    }

    private static byte[] encodeInt(int value) {
        byte[] output = new byte[4];
        ByteBuffer.wrap(output).putInt(value);
        return output;
    }

    private static int decodeInt(byte[] value) {
        if (value.length != 4)
            throw new IllegalArgumentException("Array must be of size 4");
        return ByteBuffer.wrap(value).getInt();
    }

    /**
     * set up initial seat availability for all our classes
     */
    private static void init(Database db) {
        db.run((Transaction tr) -> {
            tr.clear(Tuple.from("attends").range());
            tr.clear(Tuple.from("class").range());
            for (String className: classNames)
                initSeats(tr, className);
            return null;
        });
    }

    /**
     * get a list of classes with available seats
     */
    private static List<String> availableClasses(TransactionContext db) {
        return db.run((Transaction tr) -> {
            List<String> classNames = new ArrayList<>();
            for(KeyValue kv: tr.getRange(Tuple.from("class").range())) {
                if (decodeInt(kv.getValue()) > 0)
                    // get the second element from the tuple (the first el is the string "class")
                    classNames.add(Tuple.fromBytes(kv.getKey()).getString(1));
            }
            return classNames;
        });
    }

    private static void drop(TransactionContext db, final String s, final String c) {
        db.run((Transaction tr) -> {
            metrics.meter("txn-rate").mark();
            metrics.counter("txn").inc();
            byte[] rec = Tuple.from("attends", s, c).pack();
            if (tr.get(rec).join() == null) {
                // idempotence on retries
                metrics.counter("drop-noop").inc();
                return null; // not taking this class
            }
            byte[] classKey = Tuple.from("class", c).pack();
            tr.set(classKey, encodeInt(decodeInt(tr.get(classKey).join()) + 1));
            tr.clear(rec);
            metrics.counter("drop-success").inc();
            return null;
        });
    }

    // TODO does this rely on throwing exceptions to rollback/abort the transaction??
    private static void signup(TransactionContext db, final String s, final String c) {
        db.run((Transaction tr) -> {
            metrics.meter("txn-rate").mark();
            metrics.counter("txn").inc();
            byte[] rec = Tuple.from("attends", s, c).pack();
            if (tr.get(rec).join() != null) {
                // idempotence on retries
                metrics.counter("signup-already-signed-up").inc();
                return null; // already signed up
            }

            int seatsLeft = decodeInt(tr.get(Tuple.from("class", c).pack()).join());
            if (seatsLeft == 0) {
                metrics.counter("signup-no-seats").inc();
                throw new IllegalStateException("No remaining seats");
            }

            // TODO change the tuple to track time separately
            // TODO throw exception if time conflict
            List<KeyValue> classes = tr.getRange(Tuple.from("attends", s).range()).asList().join();
            if (classes.size() == 5) {
                metrics.counter("signup-too-many-classes").inc();
                throw new IllegalStateException("Too many classes");
            }

            tr.set(Tuple.from("class", c).pack(), encodeInt(seatsLeft - 1));
            tr.set(rec, Tuple.from("").pack());
            metrics.counter("signup-success").inc();
            return null;
        });
    }

    /**
     * switch classes atomically, without fear of exceeding class limit or getting stranded!
     */
    private static void switchClasses(TransactionContext db, final String s, final String oldC, final String newC) {
        db.run((Transaction tr) -> {
            metrics.meter("txn-rate").mark();
            metrics.counter("txn").inc();
            // drop/signup use the same transaction
            drop(tr, s, oldC);
            signup(tr, s, newC);
            metrics.counter("switch-success").inc();
            return null;
        });
    }

    //
    // Testing
    //

    private static void simulateStudents(int i, int ops) {

        String studentID = "s" + Integer.toString(i);
        List<String> allClasses = classNames;
        List<String> myClasses = new ArrayList<>();

        String clazz;
        String oldClass;
        String newClass;
        Random random = new Random();

        for (int j=0; j<ops; j++) {
            int classCount = myClasses.size();
            List<String> moods = new ArrayList<>();
            if (classCount > 3) {
                moods.add("drop");
                moods.add("switch");
            }
            if (classCount < 5)
                moods.add("add");
            String mood = moods.get(random.nextInt(moods.size()));

            try {
                if (allClasses.isEmpty())
                    allClasses = availableClasses(db);
                switch (mood) {
                    case "add":
                        clazz = allClasses.get(random.nextInt(allClasses.size()));
                        signup(db, studentID, clazz);
                        myClasses.add(clazz);
                        break;
                    case "drop":
                        clazz = myClasses.get(random.nextInt(myClasses.size()));
                        drop(db, studentID, clazz);
                        myClasses.remove(clazz);
                        break;
                    case "switch":
                        oldClass = myClasses.get(random.nextInt(myClasses.size()));
                        newClass = allClasses.get(random.nextInt(allClasses.size()));
                        switchClasses(db, studentID, oldClass, newClass);
                        myClasses.remove(oldClass);
                        myClasses.add(newClass);
                        break;
                }
            } catch (Exception e) {
                System.out.println(e.getMessage() +  "Need to recheck available classes.");
                allClasses.clear();
            }
        }

    }

    private static void runSim(int students, final int ops_per_student) throws InterruptedException {
        List<Thread> threads = new ArrayList<>(students);
        for (int i = 0; i < students; i++) {
            final int j = i;
            threads.add(new Thread(() -> simulateStudents(j, ops_per_student)) );
        }
        for (Thread thread: threads)
            thread.start();
        for (Thread thread: threads)
            thread.join();
        System.out.format("Ran %d transactions%n", students * ops_per_student);
    }

    public static void main(String[] args) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        init(db);
        System.out.println("Initialized");
        runSim(500,100);
        List<Tuple> tuples = listEnrollments(db);
        System.out.println("found " + tuples.size() + " enrollments");
        System.out.println("tuples = " + tuples);
        reporter.report();
        System.out.println("stopwatch = " + stopwatch);
    }

    private static List<Tuple> listEnrollments(TransactionContext db) {
        return db.run((Transaction tr) -> {
            List<Tuple> enrollments = new ArrayList<>();
            for(KeyValue kv: tr.getRange(Tuple.from("attends").range())) {
                    Tuple key = Tuple.fromBytes(kv.getKey());
                    enrollments.add(key);
            }
            return enrollments;
        });
    }
}