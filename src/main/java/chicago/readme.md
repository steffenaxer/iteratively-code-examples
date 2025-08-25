# MATSim Chicago TNP Scenario Toolkit

This repository provides a set of tools to generate and execute MATSim scenarios based on the Chicago TNP (Transportation Network Providers) dataset. It includes three main components:

- **ScenarioCreator.java** – Sets up a complete MATSim scenario.
- **PlansConverter.java** – Converts Chicago TNP trip data into MATSim-compatible agent plans.
- **SimulationExecutor.java** – Runs the simulation using MATSim with advanced DRT extensions.

## 📦 Components

### 1. `ScenarioCreator.java`
Creates a full MATSim scenario from scratch using:
- A bounding box and `.osm.pbf` file for network generation. You could download a suitable osm dump your pbf from [geofabrik](https://download.geofabrik.de/)
- Chicago TNP trip data via the JSON API.
- Fleet generation and configuration setup.
- Custom DRT configuration including:
  - Parallel insertion
  - Rebalancing
  - Optimization constraints
  - Zone-based analysis

**Usage:**
```bash
java -cp <your-classpath> chicago.ScenarioCreator   -w <workdir>   -u <osm.pbf URL>   -k <network key>   -e <EPSG code>   -S <start date>   -E <end date>   -t <API token>   -bbox <xmin,ymin,xmax,ymax>   [-c <census tract file>]   [-r <sample rate>]
```

### 2. `PlansConverter.java`
Downloads and converts trip data from the Chicago TNP dataset into MATSim agent plans. 
Cenus tract shapes files could be downloaded [here](https://www.census.gov/geographies/mapping-files/time-series/geo/tiger-line-file.html)

Supports:
- Sampling rate (default 1.0, no sampling)
- Coordinate transformation
- Optional census tract-based location sampling
- Caching of API responses
- Output of `plans.xml.gz` and `trips.csv.gz`

**Usage:**
```bash
java -cp <your-classpath> chicago.PlansConverter   -t <API token>   -w <workdir>   -S <start date>   [-E <end date>]   -e <EPSG code>   [-c <census tract file>]   [-r <sample rate>]
```

### 3. `SimulationExecutor.java`
Executes a MATSim simulation using the generated scenario. It supports:
- Parallel request insertion
- Spatial filtering
- Multi-mode DRT configuration

**Usage:**
```bash
java -cp <your-classpath> chicago.SimulationExecutor   -c <path to config.xml>   -o <output directory>
```

## 🧠 Author
Developed by **Steffen Axer**  
Mobility Strategist & MATSim Expert  
[iteratively.io](https://iteratively.io)

## 📝 License
This project is released under the MIT License.
