package com.hpcloud.mon.persister.repository;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import com.hpcloud.mon.persister.configuration.MonPersisterConfiguration;
import io.dropwizard.setup.Environment;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Serie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class InfluxDBMetricRepository implements MetricRepository {

    private static final Logger logger = LoggerFactory.getLogger(InfluxDBMetricRepository.class);

    private final MonPersisterConfiguration configuration;
    private final Environment environment;
    private final InfluxDB influxDB;

    private final List<Measurement> measurementList = new LinkedList<>();
    private final Map<Sha1HashId, Definition> definitionMap = new HashMap<>();
    private final Map<Sha1HashId, List<Dimension>> dimensionMap = new HashMap<>();
    private final Map<Sha1HashId, DefinitionDimension> definitionDimensionMap = new HashMap<>();

    private final com.codahale.metrics.Timer flushTimer;
    public final Meter measurementMeter;

    @Inject
    public InfluxDBMetricRepository(MonPersisterConfiguration configuration,
                                    Environment environment) {
        this.configuration = configuration;
        this.environment = environment;
        influxDB = InfluxDBFactory.connect(configuration.getInfluxDBConfiguration().getUrl(),
                configuration.getInfluxDBConfiguration().getUser(),
                configuration.getInfluxDBConfiguration().getPassword());

        this.flushTimer = this.environment.metrics().timer(this.getClass().getName() + "." + "flush-timer");
        this.measurementMeter = this.environment.metrics().meter(this.getClass().getName() + "." + "measurement-meter");

    }

    @Override
    public void addMetricToBatch(Sha1HashId defDimsId, String timeStamp, double value) {
        Measurement m = new Measurement(defDimsId, timeStamp, value);
        this.measurementList.add(m);

    }

    @Override
    public void addDefinitionToBatch(Sha1HashId defId, String name, String tenantId, String region) {
        Definition d = new Definition(defId, name, tenantId, region);
        definitionMap.put(defId, d);
    }

    @Override
    public void addDimensionToBatch(Sha1HashId dimSetId, String name, String value) {
        List<Dimension> dimensionList = dimensionMap.get(dimSetId);
        if (dimensionList == null) {
            dimensionList = new LinkedList<Dimension>();
            dimensionMap.put(dimSetId, dimensionList);
        }

        Dimension d = new Dimension(dimSetId, name, value);
        dimensionList.add(d);
    }

    @Override
    public void addDefinitionDimensionToBatch(Sha1HashId defDimsId, Sha1HashId defId, Sha1HashId dimId) {
        DefinitionDimension dd = new DefinitionDimension(defDimsId, defId, dimId);
        definitionDimensionMap.put(defDimsId, dd);
    }

    @Override
    public void flush() {

        try {
            long startTime = System.currentTimeMillis();
            Timer.Context context = flushTimer.time();
            Map<Sha1HashId, Map<Set<String>, List<Point>>> defMap = getInfluxDBFriendlyMap();
            Serie[] series = getSeries(defMap);
            this.influxDB.write(this.configuration.getInfluxDBConfiguration().getName(), series, TimeUnit.SECONDS);
            long endTime = System.currentTimeMillis();
            context.stop();
            logger.debug("Writing measurements, definitions, and dimensions to database took " + (endTime - startTime) / 1000 + " seconds");
        } catch (Exception e) {
            logger.error("Failed to write measurements to database", e);
        }
        clearBuffers();
    }

    private Serie[] getSeries(Map<Sha1HashId, Map<Set<String>, List<Point>>> defMap) throws Exception {

        logger.debug("Creating series array of size: " + definitionMap.size());
        Serie[] series = new Serie[definitionMap.size()];
        int i = 0;

        for (Sha1HashId defId : defMap.keySet()) {

            Definition definition = definitionMap.get(defId);
            if (definition == null) {
                throw new Exception("Failed to find Definition for defId: " + defId);
            }

            Serie serie = new Serie(definition.name);
            logger.debug("Created serie: " + serie.getName());

            Map<Set<String>, List<Point>> dimNameSetMap = defMap.get(defId);

            for (Set<String> dimNameSet : dimNameSetMap.keySet()) {

                // Add 4 for the tenant id, region, timestamp, and value.
                String[] colNameStringArry = new String[dimNameSet.size() + 4];
                logger.debug("Adding column name[0]: tenant_id");
                colNameStringArry[0] = "tenant_id";
                logger.debug("Adding column name[1]: region");
                colNameStringArry[1] = "region";
                int j = 2;
                for (String dimName : dimNameSet) {
                    logger.debug("Adding column name[{}]: " + dimName, j);
                    colNameStringArry[j++] = dimName;
                }
                logger.debug("Adding column name[{}]: time_stamp", j);
                colNameStringArry[j++] = "time_stamp";
                logger.debug("Adding column name[{}]: value", j);
                colNameStringArry[j++] = "value";

                serie.setColumns(colNameStringArry);

                if (logger.isDebugEnabled()) {
                    logger.debug("Added array of column names to serie");
                    StringBuffer sb = new StringBuffer();
                    boolean first = true;
                    for (String colName : serie.getColumns()) {
                        if (first) {
                            first = false;
                        } else {
                            sb.append(",");
                        }
                        sb.append(colName);
                    }
                    logger.debug("Array of column names: [" + sb.toString() + "]");
                }

                List<Point> pointList = dimNameSetMap.get(dimNameSet);
                if (pointList == null) {
                    throw new Exception("Failed to find point list for dimension set:\n" + dimNameSet);
                }

                // Add 4 for the tenant id, region, timestamp, and value.
                Object[][] colValsObjectArry = new Object[pointList.size()][dimNameSet.size() + 4];
                int k = 0;
                for (Point point : pointList) {
                    logger.debug("Adding column value[{}][0]: " + definition.tenantId, k, 0);
                    colValsObjectArry[k][0] = definition.tenantId;
                    logger.debug("Adding column value[{}][1]: " + definition.region, k, 1);
                    colValsObjectArry[k][1] = definition.region;
                    int l = 2;
                    for (String dimName : dimNameSet) {
                        String dimVal = point.dimValMap.get(dimName);
                        if (dimVal == null) {
                            throw new Exception("Failed to find dimension value for dimension name: " + dimName);
                        }
                        logger.debug("Adding column value[{}][{}]: " + dimVal, k, l);
                        colValsObjectArry[k][l++] = dimVal;

                    }
                    logger.debug("Adding column value[{}][{}]: " + point.measurement.timeStamp, k, l);
                    colValsObjectArry[k][l++] = point.measurement.timeStamp;
                    logger.debug("Adding column value[{}][{}]: " + point.measurement.value, k, l);
                    colValsObjectArry[k][l++] = point.measurement.value;
                    k++;
                }
                serie.setPoints(colValsObjectArry);

                if (logger.isDebugEnabled()) {
                    logger.debug("Added array of array of column values to serie");
                    int outerIdx = 0;
                    for (Object[] colValArry : serie.getPoints()) {
                        StringBuffer sb = new StringBuffer();
                        boolean first = true;
                        for (Object colVal : colValArry) {
                            if (first) {
                                first = false;
                            } else {
                                sb.append(",");
                            }
                            sb.append(colVal);
                        }
                        logger.debug("Array of column values[{}]: [" + sb.toString() + "]", outerIdx);
                        outerIdx++;
                    }
                }

            }
            logger.debug("Adding serie: " + serie.getName());
            series[i++] = serie;

        }
        return series;
    }

    /**
     * Group all measurements with the same dimension names into a list.
     * Generate a map of definition id's to map of dimension name sets to list of points.
     *
     * @return
     * @throws Exception
     */
    private Map<Sha1HashId, Map<Set<String>, List<Point>>> getInfluxDBFriendlyMap() throws Exception {

        Map<Sha1HashId, Map<Set<String>, List<Point>>> defMap = new HashMap<>();

        for (Measurement measurement : measurementList) {

            DefinitionDimension definitionDimension = definitionDimensionMap.get(measurement.defDimsId);
            if (definitionDimension == null) {
                throw new Exception("Failed to find DefinitionDimension for measurement:\n" + measurement);
            }

            List<Dimension> dimensionList = dimensionMap.get(definitionDimension.dimId);
            if (dimensionList == null) {
                throw new Exception("Failed to find Dimensions for measurement:\n" + measurement);
            }

            Map<Set<String>, List<Point>> dimNameSetMap = defMap.get(definitionDimension.defId);
            if (dimNameSetMap == null) {
                dimNameSetMap = new HashMap<Set<String>, List<Point>>();
                defMap.put(definitionDimension.defId, dimNameSetMap);
            }

            // create set of dimension names.
            TreeSet<String> dimNameSet = new TreeSet<>();
            // create a map from dimension names to values for this measurement.
            Map<String, String> dimValueMap = new HashMap<>();
            for (Dimension dimension : dimensionList) {
                dimNameSet.add(dimension.name);
                dimValueMap.put(dimension.name, dimension.value);
            }

            List<Point> pointList = dimNameSetMap.get(dimNameSet);
            if (pointList == null) {
                pointList = new LinkedList<Point>();
                dimNameSetMap.put(dimNameSet, pointList);
            }

            Point point = new Point();
            point.measurement = measurement;
            point.dimValMap = dimValueMap;

            pointList.add(point);

        }

        return defMap;
    }

    private void clearBuffers() {

        this.measurementList.clear();
        this.definitionMap.clear();
        this.dimensionMap.clear();
        this.definitionDimensionMap.clear();
    }

    private static final class Measurement {
        Sha1HashId defDimsId;
        String timeStamp;
        double value;

        private Measurement(Sha1HashId defDimsId, String timeStamp, double value) {
            this.defDimsId = defDimsId;
            this.timeStamp = timeStamp;
            this.value = value;
        }

        @Override
        public String toString() {
            return "Measurement{" +
                    "defDimsId=" + defDimsId +
                    ", timeStamp='" + timeStamp + '\'' +
                    ", value=" + value +
                    '}';
        }
    }

    private static final class Definition {
        Sha1HashId defId;
        String name;
        String tenantId;
        String region;

        private Definition(Sha1HashId defId, String name, String tenantId, String region) {
            this.defId = defId;
            this.name = name;
            this.tenantId = tenantId;
            this.region = region;
        }

        @Override
        public String toString() {
            return "Definition{" +
                    "defId=" + defId +
                    ", name='" + name + '\'' +
                    ", tenantId='" + tenantId + '\'' +
                    ", region='" + region + '\'' +
                    '}';
        }
    }

    private static final class Dimension {
        Sha1HashId dimSetId;
        String name;
        String value;

        private Dimension(Sha1HashId dimSetId, String name, String value) {
            this.dimSetId = dimSetId;
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return "Dimension{" +
                    "dimSetId=" + dimSetId +
                    ", name='" + name + '\'' +
                    ", value='" + value + '\'' +
                    '}';
        }
    }

    private static final class DefinitionDimension {
        Sha1HashId defDimId;
        Sha1HashId defId;
        Sha1HashId dimId;

        private DefinitionDimension(Sha1HashId defDimId, Sha1HashId defId, Sha1HashId dimId) {
            this.defDimId = defDimId;
            this.defId = defId;
            this.dimId = dimId;
        }

        @Override
        public String toString() {
            return "DefinitionDimension{" +
                    "defDimId=" + defDimId +
                    ", defId=" + defId +
                    ", dimId=" + dimId +
                    '}';
        }
    }

    private static final class Point {
        Measurement measurement;
        Map<String, String> dimValMap;

        @Override
        public String toString() {
            return "Point{" +
                    "measurement=" + measurement +
                    ", dimValMap=" + dimValMap +
                    '}';
        }
    }
}