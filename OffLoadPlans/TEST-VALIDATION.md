# Test Validation Guide for OffloadModuleExampleTest

## Overview
This document explains how to run and validate the `OffloadModuleExampleTest` which ensures that the PlanProxy architecture correctly maintains the materialization constraint: **at most 1 plan materialized per person at runtime**.

## Test Location
```
OffLoadPlans/src/test/java/io/iteratively/matsim/offload/example/OffloadModuleExampleTest.java
```

## Running the Test

### Option 1: Run all tests
```bash
cd OffLoadPlans
mvn test
```

### Option 2: Run only OffloadModuleExampleTest
```bash
cd OffLoadPlans
mvn test -Dtest=OffloadModuleExampleTest
```

### Option 3: Use the convenience script
```bash
cd OffLoadPlans
./run-test.sh
```

## What the Test Validates

The test includes two test methods:

### 1. `testExampleRunsSuccessfully()`
- Runs a complete MATSim simulation with the offload module
- Uses the `MaterializationValidator` listener to check constraints at every iteration
- Verifies:
  - ✅ Simulation completes successfully
  - ✅ MapDB file is created and contains data
  - ✅ At iteration start: at most 1 plan materialized per person
  - ✅ At iteration end: 0 plans materialized per person

### 2. `testAtMostOnePlanMaterializedPerPerson()`
- Runs a simulation with detailed tracking of materialization
- Uses custom listeners to count materialized plans
- Tracks maximum materialized plans across all iterations
- Validates:
  - ✅ At most 1 plan materialized per person throughout entire simulation
  - ✅ All plans dematerialized after each iteration end
  - ✅ Prints summary statistics about materialization

## Expected Output

When the test runs successfully, you should see output like:
```
Iteration 0 start: Total materialized=X, Max per person=1
Iteration 0 end: Total materialized=0
Iteration 1 start: Total materialized=X, Max per person=1
Iteration 1 end: Total materialized=0
...

Test Summary:
Max materialized plans total: X
Max materialized per person: 1
```

## Test Configuration

The test is configured to:
- Run 2-3 iterations (short test for quick validation)
- Use Siouxfalls test scenario from matsim-examples
- Set `maxAgentPlanMemorySize` to 3-5 plans
- Use a temporary directory for plan storage

## Maven Configuration

The test is picked up by Maven Surefire because:
1. File name ends with `Test.java`
2. pom.xml includes pattern: `**/*Test.java`
3. Test uses JUnit Jupiter (`@Test` annotations)
4. Properly located in `src/test/java`

## Dependencies Required

The test requires these dependencies (already in pom.xml):
- `matsim` (main dependency)
- `matsim-examples` (test scope - provides ExamplesUtils and test scenarios)
- `matsim` test-jar (test scope - provides MatsimTestUtils)
- `junit-jupiter-api` and `junit-jupiter-engine` (test scope)

## Troubleshooting

If the test doesn't run:

1. **Check dependencies**: Ensure `matsim-examples` is available
   ```bash
   mvn dependency:tree | grep matsim-examples
   ```

2. **Verify test compilation**: 
   ```bash
   mvn test-compile
   ```

3. **Check Maven Surefire version**: Should be 3.2.5 or later
   ```bash
   mvn help:effective-pom | grep surefire
   ```

4. **Run with debug output**:
   ```bash
   mvn test -Dtest=OffloadModuleExampleTest -X
   ```

## Success Criteria

The test passes when:
- ✅ Both test methods complete without exceptions
- ✅ No assertion failures
- ✅ Maximum materialized plans per person = 1
- ✅ Plans dematerialized to 0 at each iteration end
- ✅ MapDB file created with data

This validates that the PlanProxy architecture successfully maintains memory efficiency while providing full plan selector functionality.
