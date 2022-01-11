# Mod Downloader

[![a12 maintenance: Slowly](https://api.anatawa12.com/short/a12-slowly-svg)](https://api.anatawa12.com/short/a12-slowly-doc)
[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/anatawa12/mod-downloader)](https://github.com/anatawa12/mod-downloader/releases/latest)

The tool to download mods automatically.

You can download the latest version from [release page](https://github.com/anatawa12/mod-downloader/releases/latest).

## How to use

### GUI

Download jar and just double-click the jar.

### CUI

Download jar and run ``java -jar path/to/mod-downloader.jar --help`` for more informtion.

## Config file format

```text
# to download from curseforge
mod <modid in this file> from curse <curse mod slug>
    version <fileid> (<optional version name>)
# to download via url
mod <modid in this file> from url "<url to jar you can use $version as placeholder>"
    version <version name> (<optional version name>)
```

example

```text
mod fixrtm from curse fixrtm
    version 3522183 (2.0.20)

mod preload-newer-kotlin from url "https://github.com/anatawa12/preload-newer-kotlin/releases/download/v$version/aaaa-preload-newer-kotlin-$version.jar"
    version 1.6.10
```

## config-embed mod-downloader

You can make and redistribute a mod-downloader with embed configuration.

```bash
java -jar "/path/to/mod-downloader.jar" --new-embed \
    --config path/to/config.txt \
    --name "<name of server>" \
    --dest path/to/dest.jar
```

You can get more information via the following command.

```bash
java -jar "/path/to/mod-downloader.jar" --new-embed --help
```

## Known Problems

- on MacOS, we can't choose files in Desktop ([JDK-8264789])

[JDK-8264789]: https://bugs.openjdk.java.net/browse/JDK-8264789
