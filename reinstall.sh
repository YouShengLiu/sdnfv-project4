#!/bin/bash
mvn clean install -DskipTests
onos-app localhost deactivate nctu.winlab.unicastdhcp
onos-app localhost uninstall nctu.winlab.unicastdhcp
# onos-app localhost install! target/unicastdhcp-1.0-SNAPSHOT.oar
