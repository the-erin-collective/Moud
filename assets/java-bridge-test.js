// Enhanced test script to verify Java Bridge Access and Plugin Registration
// This specifically tests the resolveJavaBridge() functionality

console.log("🔍 Testing Java Bridge Access and Plugin Registration...");
console.log("📋 Verifying enhanced JavaScript runtime with Java bridge injection...");

// Test 1: Check if basic JavaScript objects are available
console.log("🌍 Basic Global Objects:");
console.log("- api available:", typeof api !== 'undefined');
console.log("- Moud available:", typeof Moud !== 'undefined');
console.log("- console available:", typeof console !== 'undefined');

// Test 2: CRITICAL - Test Java bridge access (what resolveJavaBridge() needs)
console.log("☕ Java Bridge Access Test:");
console.log("- globalThis.Java available:", typeof globalThis.Java !== 'undefined');
console.log("- globalThis.Java.type available:", typeof globalThis.Java?.type !== 'undefined');

if (typeof globalThis.Java !== 'undefined') {
    console.log("✅ Java bridge found! Testing Java access...");
    
    try {
        // Test Java.type functionality
        const StringClass = globalThis.Java.type('java.lang.String');
        console.log("- Java.type('java.lang.String') works:", StringClass !== null);
        
        const SystemClass = globalThis.Java.type('java.lang.System');
        console.log("- Java.type('java.lang.System') works:", SystemClass !== null);
        
        // Test creating Java objects
        const testString = new StringClass("Hello from JavaScript!");
        console.log("- Can create Java String:", testString !== null);
        console.log("- Java String value:", testString);
        
        // Test Java static methods
        const currentTime = SystemClass.currentTimeMillis();
        console.log("- Can call Java System.currentTimeMillis():", currentTime);
        
        console.log("✅ Java bridge fully functional!");
        
    } catch (error) {
        console.error("❌ Java bridge access failed:", error);
    }
} else {
    console.log("❌ Java bridge not available - resolveJavaBridge() will fail");
}

// Test 3: Test api.java access (alternative path for resolveJavaBridge)
console.log("🔗 API Java Bridge Access:");
console.log("- api.java available:", typeof api?.java !== 'undefined');
console.log("- api.java.type available:", typeof api?.java?.type !== 'undefined');

if (typeof api?.java !== 'undefined') {
    try {
        const StringClass = api.java.type('java.lang.String');
        console.log("- api.java.type works:", StringClass !== null);
        console.log("✅ API Java bridge functional!");
    } catch (error) {
        console.error("❌ API Java bridge failed:", error);
    }
}

// Test 4: Simulate resolveJavaBridge() function behavior
console.log("🔍 Simulating resolveJavaBridge() behavior:");
function simulateResolveJavaBridge() {
    const candidates = [
        globalThis.Java,
        globalThis.moud?.java,
        globalThis.moud?.native,
        globalThis.Moud?.java,
        globalThis.api?.java,
        globalThis.api,
        globalThis.moud,
        globalThis.Moud
    ];
    
    console.log("Testing resolveJavaBridge candidates:");
    candidates.forEach((candidate, index) => {
        const available = candidate !== null && candidate !== undefined;
        console.log(`  Candidate ${index}: ${available ? '✅' : '❌'}`);
        if (available && typeof candidate === 'object') {
            console.log(`    - Has type method: ${typeof candidate.type !== 'undefined' ? '✅' : '❌'}`);
            console.log(`    - Has System class: ${typeof candidate.System !== 'undefined' ? '✅' : '❌'}`);
        }
    });
    
    // Find first available candidate
    const javaBridge = candidates.find(c => c !== null && c !== undefined);
    console.log("resolveJavaBridge() result:", javaBridge !== null ? '✅ Found' : '❌ Not found');
    return javaBridge;
}

const javaBridge = simulateResolveJavaBridge();

// Test 5: If Java bridge is available, try to access BridgeRegistry
if (javaBridge) {
    console.log("🌉 Testing BridgeRegistry access via Java bridge...");
    
    try {
        // Try to access the BridgeRegistry class
        const BridgeRegistryClass = javaBridge.type('com.moud.plugin.api.BridgeRegistry');
        console.log("- BridgeRegistry class accessible:", BridgeRegistryClass !== null);
        
        if (BridgeRegistryClass) {
            // Try to get registered names
            const registeredNames = BridgeRegistryClass.getRegisteredNames();
            console.log("- getRegisteredNames() works:", registeredNames !== null);
            
            if (registeredNames) {
                console.log("- Registered bridge names:", registeredNames);
                
                // Try to get a specific bridge service
                if (registeredNames.length > 0) {
                    const firstBridgeName = registeredNames[0];
                    console.log(`- Testing access to bridge: ${firstBridgeName}`);
                    
                    const bridgeService = BridgeRegistryClass.get(firstBridgeName);
                    console.log(`- Bridge service '${firstBridgeName}' accessible:`, bridgeService !== null);
                    
                    if (bridgeService) {
                        console.log(`- Bridge service class:`, bridgeService.getClass().getSimpleName());
                        console.log("✅ BridgeRegistry access successful!");
                    }
                }
            }
        }
        
    } catch (error) {
        console.error("❌ BridgeRegistry access failed:", error);
        console.log("This might be expected if the bridge class path is different");
    }
}

// Test 6: Check for any bridge services that should be globally available
console.log("🌍 Checking for globally registered bridge services:");
const globalKeys = Object.keys(this);
const bridgeServices = globalKeys.filter(key => 
    key !== 'setTimeout' && 
    key !== 'setInterval' && 
    key !== 'clearInterval' &&
    key !== 'console' &&
    key !== 'bridgeReady' &&
    key !== 'areBridgesReady' &&
    key !== 'getBridgeStatus' &&
    key !== 'api' &&
    key !== 'Moud' &&
    key !== 'Java'
);

console.log("Potential bridge services found:", bridgeServices);

// Test each potential bridge service
bridgeServices.forEach(serviceName => {
    const service = this[serviceName];
    console.log(`- ${serviceName}:`, typeof service !== 'undefined' ? '✅' : '❌');
    
    if (typeof service !== 'undefined' && typeof service === 'object') {
        const methods = Object.getOwnPropertyNames(service).filter(name => 
            typeof service[name] === 'function'
        );
        console.log(`  Methods: ${methods.join(', ')}`);
    }
});

console.log("🎉 Java Bridge Test Completed!");
console.log("✅ If Java bridge access is working, resolveJavaBridge() should succeed!");
console.log("✅ If BridgeRegistry access is working, plugin registration should be visible!");
