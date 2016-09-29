# Large Enterprise Performance Reproduction Project

## Getting Started

Clone the repo:
```sh
$> git clone https://github.com/gradle/perf-enterprise-large.git
$> cd perf-enterprise-large
```

From here you need to run the generation script. It takes about 30 seconds to do it's work.
```sh
$> ./setup.sh
```

At this point, you should be able to run the build.

```sh
$> gradle -g gradle-user-home resolveDependencies
```

## Integrated profiling tools

Use profiling scripts from [perf-native-large](https://github.com/gradle/perf-native-large).

Add a symbolic link to the profiler directory
```
ln -s ../perf-native-large/profiler
```

[Follow instructions](https://github.com/gradle/perf-native-large#integrated-profiling-tools)