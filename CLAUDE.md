# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Maven-based Java 21 project for generating or managing Instagram posts for the `thevilnius2` account. Currently in early scaffolding stage.

## Build & Run

```bash
# Compile
mvn compile

# Run (entry point: org.example.Main)
mvn exec:java -Dexec.mainClass="org.example.Main"

# Package
mvn package

# Clean build
mvn clean package
```

No test framework is configured yet. Add dependencies to `pom.xml` before writing tests.
