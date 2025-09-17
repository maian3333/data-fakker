package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RemoveDuplicateLines {
    public static void main(String[] args) throws IOException {
        // Input file
        Path input = Paths.get("post_titles.csv");
        // Output file
        Path output = Paths.get("output.txt");

        // Use LinkedHashSet to keep order and remove duplicates
        Set<String> lines = new LinkedHashSet<>(Files.readAllLines(input));

        // Write back to file
        Files.write(output, lines);

        System.out.println("✅ Duplicates removed. Saved to " + output.toAbsolutePath());
    }
}
