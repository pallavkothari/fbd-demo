package com.pk.foundationdbdemo;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.tuple.Tuple;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HelloWorld {


    @Test
    public void testConnection() {
        FDB fdb = FDB.selectAPIVersion(510);

        try(Database db = fdb.open()) {
            // Run an operation on the database
            db.run(tr -> {
                tr.set(Tuple.from("hello").pack(), Tuple.from("world").pack());
                return null;
            });

            // Get the value of 'hello' from the database
            String hello = db.run(tr -> {
                byte[] result = tr.get(Tuple.from("hello").pack()).join();
                return Tuple.fromBytes(result).getString(0);
            });

            System.out.println("Hello " + hello);
            assertThat(hello, is("world"));
        }

    }
}
