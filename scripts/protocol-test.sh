#!/bin/bash

# Protocol Test Script
# 用于测试pt-common的协议实现

echo "=== Protocol Test Script ==="
echo

# 设置Java环境
JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java)))/..)/current)}
export JAVA_HOME

# 运行ProtocolTestHelper
echo "1. Running ProtocolTestHelper..."
java -cp "$(pwd)/pt-common/target/classes" org.jpstale.server.common.testing.ProtocolTestHelper

echo
echo "2. To run the test client:"
echo "   java -cp pt-common/target/classes org.jpstale.server.common.testing.TestClient [host] [port]"
echo "   Example: java -cp pt-common/target/classes org.jpstale.server.common.testing.TestClient localhost 10009"
echo
echo "3. Testing packet hex:"
echo "   You can also manually inspect the hex output above to compare with C++ server packets"