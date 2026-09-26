# Gradle Dependency Leak

A single-module Gradle project whose leaking code calls into a Maven Central
dependency (commons-io). It checks that dependency jars discovered from the
build reach every analysis and repair step: the Resource Leak Checker, the
stage tools' compile checks, and RLFixer's WALA class hierarchy.

Resolving the dependency needs network access (or a warm Gradle cache).
