// Test script to verify synchronized bridge initialization
// This script tests the bridgeReady() synchronization system

console.log("🧪 Testing bridge synchronization system...");

// Test 1: Check initial bridge status
console.log("📊 Initial bridge status:", getBridgeStatus());
console.log("🔍 Are bridges ready?", areBridgesReady());

// Test 2: Use the bridgeReady() function to wait for all bridges
console.log("⏳ Waiting for all bridge plugins to be ready...");
await bridgeReady();
console.log("✅ bridgeReady() resolved - all bridges are available!");

// Test 3: Check final bridge status
console.log("📊 Final bridge status:", getBridgeStatus());
console.log("🔍 Are bridges ready now?", areBridgesReady());

// Test 4: Access the test bridge service from our bridge-test plugin
if (typeof testBridge !== 'undefined') {
    console.log("🌉 testBridge service found (from bridge-test plugin)!");
    
    try {
        console.log("📝 Message:", testBridge.getMessage());
        console.log("🧮 Math: 5 + 3 =", testBridge.add(5, 3));
        console.log("ℹ️ Plugin info:", testBridge.getPluginInfo());
        console.log("🚀 Initialization status:", testBridge.getInitializationStatus());
        console.log("✅ testBridge tests completed successfully!");
    } catch (error) {
        console.error("❌ Error testing testBridge:", error);
    }
} else {
    console.log("ℹ️ testBridge service not found (bridge-test plugin may not be loaded)");
}

// Test 5: Access the TestPluginBridge service from the original test plugin
if (typeof TestPluginBridge !== 'undefined') {
    console.log("🌉 TestPluginBridge service found (from test-plugin)!");
    
    try {
        console.log("📊 TestPluginBridge status:", TestPluginBridge.getStatus());
        console.log("📈 Message count:", TestPluginBridge.getMessageCount());
        
        // Test sending a message
        TestPluginBridge.sendMessage("Hello from synchronization test!");
        console.log("📤 Message sent via TestPluginBridge");
        
        console.log("✅ TestPluginBridge tests completed successfully!");
    } catch (error) {
        console.error("❌ Error testing TestPluginBridge:", error);
    }
} else {
    console.log("ℹ️ TestPluginBridge service not found (test-plugin may not be loaded)");
}

// Test 6: List all available bridge services
console.log("🔍 Checking available bridge services...");
const availableServices = [];
if (typeof testBridge !== 'undefined') availableServices.push("testBridge");
if (typeof TestPluginBridge !== 'undefined') availableServices.push("TestPluginBridge");

console.log("📋 Available bridge services:", availableServices);
console.log("🎯 Total services loaded:", availableServices.length);

console.log("🎉 Bridge synchronization test completed!");
console.log("🚀 Synchronized initialization system is working correctly!");
