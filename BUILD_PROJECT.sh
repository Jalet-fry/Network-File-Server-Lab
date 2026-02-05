#!/bin/bash
echo "Building project for Unix-like system..."
chmod +x gradlew
./gradlew installDist
if [ $? -eq 0 ]; then
    echo "Build successful. Use ./run.sh to start."
else
    echo "Build failed."
fi
