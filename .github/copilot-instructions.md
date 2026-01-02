# Copilot Instructions for iteratively-code-examples

## Repository Purpose
This repository contains code examples that accompany blog posts published on [iteratively.io](https://iteratively.io). Each example demonstrates practical applications of transportation simulation and data analysis concepts discussed in the articles.

## Project Structure
- **Java Code**: Located in `src/main/java/` - organized by topic/blog post (e.g., `chicago/`)
- **Python Code**: Located in `src/main/python/` - contains Jupyter notebooks for data analysis
- **Resources**: Located in `src/main/resources/` - configuration files and input data

## Technology Stack

### Java
- **Language Version**: Java 25
- **Build Tool**: Maven
- **Main Framework**: MATSim (Multi-Agent Transport Simulation)
  - Version: 2026.0-2025w50
  - MATSim contrib modules: OSM, DVRP, DRT, DRT-extensions
- **Key Dependencies**:
  - Apache Commons CLI for command-line argument parsing
  - Log4j 2 for logging
  - JSON processing
  - Osmosis for OpenStreetMap data handling

### Python
- **Analysis Tools**: Jupyter notebooks for data visualization and analysis
- **Key Libraries**: pandas, matplotlib, seaborn, scipy

## Build and Run Instructions

### Java
- **Build**: `mvn clean install`
- **Compiler**: Configured for Java 25 (source and target)
- **Maven Repository**: Uses custom MATSim repository at `https://repo.matsim.org/repository/matsim`

### Python
- **Setup**: Install dependencies with `pip install -r src/main/python/requirements.txt`
- **Virtual Environment**: Use `src/main/python/env` (excluded from git)

## Code Organization Principles
- Each blog post topic has its own package/directory
- Examples are self-contained with their own main classes
- Configuration files are separated in resources
- Output files go to `/outputs/` (excluded from git)

## Current Examples

### Chicago DRT Simulation
**Topic**: Autonomous fleet sizing for Chicago using MATSim DRT
- **Blog Post**: [How Many Cars Would Waymo Need in Chicago?](https://iteratively.io/p/drt-chicago/)
- **Code Location**: `src/main/java/chicago/`
- **Key Classes**:
  - `SimulationExecutor`: Main entry point for running simulations
  - `NetworkConverter`: Converts OSM data to MATSim network
  - `ScenarioCreator`: Sets up simulation scenarios
  - `FleetGenerator`: Creates vehicle fleets
  - `TripRecordCsvWriter`: Exports trip data
- **Analysis Notebooks**: `src/main/python/chicago/notebooks/`

## Coding Conventions
- Follow standard Java naming conventions
- Use descriptive class and method names
- Include author annotations in Java classes
- Command-line tools should use Apache Commons CLI for argument parsing
- Keep examples focused and aligned with their corresponding blog posts

## When Contributing New Examples
1. Create a new package under `src/main/java/` named after the topic
2. Add corresponding Python analysis notebooks if applicable
3. Update the main readme.md with links to the blog post and code
4. Ensure all dependencies are properly declared in `pom.xml` or `requirements.txt`
5. Follow the existing pattern of self-contained, runnable examples
