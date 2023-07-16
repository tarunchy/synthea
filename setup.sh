#!/bin/bash

# Define versions
JDK_VERSION="jdk-11.0.11+9"
MAVEN_VERSION="3.9.3"

# Check if JDK is already installed
if type -p java; then
    echo "Java is already installed"
else
    # Download the OpenJDK 11 tar.gz
    curl -L -o /tmp/OpenJDK11U-jdk_x64_linux_openj9_11.0.11_9_openj9-0.26.0.tar.gz https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-11.0.11%2B9_openj9-0.26.0/OpenJDK11U-jdk_x64_linux_openj9_11.0.11_9_openj9-0.26.0.tar.gz

    # Extract the tar.gz file
    tar xzf /tmp/OpenJDK11U-jdk_x64_linux_openj9_11.0.11_9_openj9-0.26.0.tar.gz -C /tmp

    # Move the extracted directory to /usr/lib/jvm
    sudo mv /tmp/$JDK_VERSION /usr/lib/jvm/

    # Remove the downloaded tar.gz file
    rm /tmp/OpenJDK11U-jdk_x64_linux_openj9_11.0.11_9_openj9-0.26.0.tar.gz

    echo "Java has been successfully installed"
fi

# Check if Maven is already installed
if type -p mvn; then
    echo "Maven is already installed"
else
    # Download Apache Maven
    curl -L -o /tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz

    # Extract the tar.gz file
    tar xf /tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz -C /tmp

    # Move the extracted directory to /opt
    sudo mv /tmp/apache-maven-$MAVEN_VERSION /opt/maven

    # Set up the environment variables
    echo "export JAVA_HOME=\"/usr/lib/jvm/$JDK_VERSION\"" | sudo tee -a /etc/environment
    echo "export M2_HOME=\"/opt/maven\"" | sudo tee -a /etc/environment
    echo "export MAVEN_HOME=\"/opt/maven\"" | sudo tee -a /etc/environment

    # Update the PATH variable
    echo "export PATH=\$PATH:\$M2_HOME/bin:\$MAVEN_HOME/bin" | sudo tee -a /etc/profile

    # Load the environment variables
    source /etc/environment
    source /etc/profile

    # Remove the downloaded tar.gz file
    rm /tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz

    echo "Maven has been successfully installed"
fi
