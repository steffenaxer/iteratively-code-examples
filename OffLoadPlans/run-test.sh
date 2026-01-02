#!/bin/bash
# Script to run OffloadModuleExampleTest
# This test validates that at most 1 plan is materialized per person at runtime

cd "$(dirname "$0")"

echo "Running OffloadModuleExampleTest..."
echo "This test validates the materialization constraint:"
echo "  - At iteration start: ≤1 plan materialized per person"
echo "  - At iteration end: 0 plans materialized"
echo ""

# Run the specific test
mvn test -Dtest=OffloadModuleExampleTest

# Check exit code
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Test passed! Materialization constraint validated."
else
    echo ""
    echo "❌ Test failed. Check the output above for details."
    exit 1
fi
