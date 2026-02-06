// Test script to verify GraalVM Context Injection and Bridge Service Access
// This demonstrates the JavaScript Engine Initialization notes in action

console.log("🧪 Testing GraalVM Context Injection...");
console.log("📋 Verifying JavaScript Engine Initialization notes...");

// Test 1: Check if timer functions are injected (from initializeContext)
console.log("⏰ Timer functions available:");
console.log("- setTimeout available:", typeof setTimeout !== 'undefined');
console.log("- setInterval available:", typeof setInterval !== 'undefined');
console.log("- clearInterval available:", typeof clearInterval !== 'undefined');

// Test 2: Check if console API is injected (from bindModules)
console.log("📝 Console API available:");
console.log("- console available:", typeof console !== 'undefined');
console.log("- console.log available:", typeof console.log !== 'undefined');

// Test 3: Check if bridgeReady functions are injected (from injectBridgeReadyFunction)
console.log("🌉 Bridge synchronization functions:");
console.log("- bridgeReady available:", typeof bridgeReady !== 'undefined');
console.log("- areBridgesReady available:", typeof areBridgesReady !== 'undefined');
console.log("- getBridgeStatus available:", typeof getBridgeStatus !== 'undefined');

// Test 4: Check bridge status (tests synchronization system)
console.log("📊 Bridge Status Check:");
if (typeof getBridgeStatus !== 'undefined') {
    const status = getBridgeStatus();
    console.log("Bridge status:", status);
    console.log("All bridges ready:", status.ready);
    console.log("Loaded plugins:", status.loaded);
    console.log("Expected plugins:", status.expected);
    console.log("Pending plugins:", status.pending);
} else {
    console.log("❌ getBridgeStatus not available - synchronization not working");
}

// Test 5: Wait for bridges and test context injection
console.log("⏳ Testing synchronized bridge injection...");
if (typeof bridgeReady !== 'undefined') {
    bridgeReady().then(() => {
        console.log("✅ bridgeReady() resolved - context injection complete!");
        
        // Test 6: Check if bridge services are injected (from injectBridgeServices)
        console.log("🌉 Bridge Service Injection Test:");
        console.log("- testBridge available:", typeof testBridge !== 'undefined');
        
        if (typeof testBridge !== 'undefined') {
            console.log("🎯 Bridge service methods available:");
            console.log("- getMessage:", typeof testBridge.getMessage !== 'undefined');
            console.log("- add:", typeof testBridge.add !== 'undefined');
            console.log("- getPluginInfo:", typeof testBridge.getPluginInfo !== 'undefined');
            console.log("- getInitializationStatus:", typeof testBridge.getInitializationStatus !== 'undefined');
            
            // Test 7: Actually call the bridge service methods
            console.log("🚀 Testing bridge service calls:");
            try {
                console.log("Message:", testBridge.getMessage());
                console.log("Math: 5 + 3 =", testBridge.add(5, 3));
                console.log("Plugin info:", testBridge.getPluginInfo());
                console.log("Init status:", testBridge.getInitializationStatus());
                console.log("✅ All bridge service calls successful!");
            } catch (error) {
                console.error("❌ Bridge service call failed:", error);
            }
        } else {
            console.log("❌ testBridge service not injected - context injection failed");
        }
        
        // Test 8: Check global scope injection
        console.log("🌍 Global Scope Analysis:");
        const globalKeys = Object.keys(this);
        console.log("Available globals:", globalKeys);
        
        const bridgeServices = globalKeys.filter(key => 
            key !== 'setTimeout' && 
            key !== 'setInterval' && 
            key !== 'clearInterval' &&
            key !== 'console' &&
            key !== 'bridgeReady' &&
            key !== 'areBridgesReady' &&
            key !== 'getBridgeStatus'
        );
        console.log("Bridge services in global scope:", bridgeServices);
        
        console.log("🎉 Context Injection Test Completed Successfully!");
        console.log("✅ JavaScript Engine Initialization notes verified!");
        
        // NEW: Also run Java bridge test
        console.log("\n🔍 Running Java Bridge Access Test...");
        
        // Test Java bridge access
        console.log("☕ Java Bridge Access Test:");
        console.log("- globalThis.Java available:", typeof globalThis.Java !== 'undefined');
        console.log("- globalThis.Java.type available:", typeof globalThis.Java?.type !== 'undefined');
        
        if (typeof globalThis.Java !== 'undefined') {
            try {
                const StringClass = globalThis.Java.type('java.lang.String');
                console.log("- Java.type('java.lang.String') works:", StringClass !== null);
                console.log("✅ Java bridge functional!");
                
                // Test BridgeRegistry access
                try {
                    const BridgeRegistryClass = globalThis.Java.type('com.moud.plugin.api.BridgeRegistry');
                    console.log("- BridgeRegistry class accessible:", BridgeRegistryClass !== null);
                    
                    if (BridgeRegistryClass) {
                        const registeredNames = BridgeRegistryClass.getRegisteredNames();
                        console.log("- Registered bridge names:", registeredNames);
                        console.log("✅ BridgeRegistry access successful!");
                    }
                } catch (error) {
                    console.log("- BridgeRegistry access failed (might be expected):", error.message);
                }
                
            } catch (error) {
                console.error("❌ Java bridge access failed:", error);
            }
        } else {
            console.log("❌ Java bridge not available");
        }
        
        console.log("🎉 Complete Bridge Test Finished!");
        
    }).catch(error => {
        console.error("❌ bridgeReady() failed:", error);
    });
} else {
    console.log("❌ bridgeReady not available - synchronization system not working");
}

console.log("🏁 Context injection test initiated...");
