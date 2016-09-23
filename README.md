# Large Enterprise Performance Reproduction Project

## Getting Started

Clone the repo:
```sh
$> git clone https://github.com/gradle/perf-enterprise-large.git
$> cd perf-enterprise-large
```

From here you need to run the generation script. It takes about 30 seconds to do it's work.
```sh
$> cd generator
$> ./gradlew run
```

At this point, you should be able to pop back up to the top level and run the build.

```sh
$> cd ../
$> gradle --refresh-dependencies resolveDependencies
```

## Integrated profiling tools

Use profiling scripts from [perf-native-large](https://github.com/gradle/perf-native-large).

Add a symbolic link to the profiler directory
```
ln -s ../perf-native-large/profiler
```

[Follow instructions](https://github.com/gradle/perf-native-large#integrated-profiling-tools)