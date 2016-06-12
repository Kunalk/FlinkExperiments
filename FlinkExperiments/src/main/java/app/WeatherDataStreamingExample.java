// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package app;

import model.LocalWeatherData;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import stream.sinks.elastic.LocalWeatherDataElasticSearchSink;
import stream.sinks.pgsql.LocalWeatherDataPostgresSink;
import stream.sources.csv.LocalWeatherDataSourceFunction;
import utils.DateUtilities;

import javax.annotation.Nullable;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class WeatherDataStreamingExample {

    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Use the Measurement Timestamp of the Event:
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // Path to read the CSV data from:
        final String csvStationDataFilePath = "C:\\Users\\philipp\\Downloads\\csv\\201503station.txt";
        final String csvLocalWeatherDataFilePath = "C:\\Users\\philipp\\Downloads\\csv\\201503hourly.txt";

        // Add the CSV Data Source and assign the Measurement Timestamp:
        DataStream<model.LocalWeatherData> localWeatherDataDataStream = env
                .addSource(new LocalWeatherDataSourceFunction(csvStationDataFilePath, csvLocalWeatherDataFilePath))
                .assignTimestampsAndWatermarks(new AssignerWithPeriodicWatermarks<LocalWeatherData>() {
                    @Nullable
                    @Override
                    public Watermark getCurrentWatermark() {
                        return null;
                    }

                    @Override
                    public long extractTimestamp(LocalWeatherData localWeatherData, long l) {
                        // Get the ZoneOffset of the Station, where the Measurement was collected in:
                        ZoneOffset zoneOffset = ZoneOffset.ofHours(localWeatherData.getStation().getTimeZone());
                        // Get the Date of the Measurement, which has to take the ZoneOffset into account:
                        Date measurementDate = DateUtilities.from(localWeatherData.getDate(), localWeatherData.getTime(), zoneOffset);
                        // And return the Milliseconds of this measurement:
                        return measurementDate.getTime();
                    }
                });

        // Now Perform the Analysis for the daily maximum value on the Stream:
        DataStream<model.LocalWeatherData> dailyMaxTemperature = localWeatherDataDataStream
                // Filte for Non-Null Temperature Values, because we might have missing data:
                .filter(new FilterFunction<LocalWeatherData>() {
                    @Override
                    public boolean filter(LocalWeatherData localWeatherData) throws Exception {
                        return localWeatherData.getTemperature() != null;
                    }
                })
                // Now create the keyed stream by the Station WBAN identifier:
                .keyBy(new KeySelector<LocalWeatherData, String>() {
                    @Override
                    public String getKey(LocalWeatherData localWeatherData) throws Exception {
                        return localWeatherData.getStation().getWban();
                    }
                })
                .timeWindow(Time.days(1))
                // Use the max Temperature of the day:
                .max("temperature")
                // And perform an Identity map, because we want to write all values of this day to the Database:
                .map(new MapFunction<model.LocalWeatherData, model.LocalWeatherData>() {
                    @Override
                    public model.LocalWeatherData map(model.LocalWeatherData localWeatherData) throws Exception {
                        return localWeatherData;
                    }
                });

        // Converts the general stream into the Elasticsearch specific representation with JsonAttributes:
        DataStream<elastic.model.LocalWeatherData> elasticDailyMaxTemperature = dailyMaxTemperature
                .map(new MapFunction<model.LocalWeatherData, elastic.model.LocalWeatherData>() {
                    @Override
                    public elastic.model.LocalWeatherData map(model.LocalWeatherData localWeatherData) throws Exception {
                        return elastic.converter.LocalWeatherDataConverter.convert(localWeatherData);
                    }
                });

        // Converts the general stream into the Postgres-specific representation:
        DataStream<pgsql.model.LocalWeatherData> pgsqlDailyMaxTemperature = dailyMaxTemperature
                .map(new MapFunction<model.LocalWeatherData, pgsql.model.LocalWeatherData>() {
                    @Override
                    public pgsql.model.LocalWeatherData map(model.LocalWeatherData localWeatherData) throws Exception {
                        return pgsql.converter.LocalWeatherDataConverter.convert(localWeatherData);
                    }
                });


        // Add a new ElasticSearch Sink:
        //elasticDailyMaxTemperature.addSink(new LocalWeatherDataElasticSearchSink("127.0.0.1", 9300, 100));

        // Add a new Postgres Sink:
        pgsqlDailyMaxTemperature.addSink(new LocalWeatherDataPostgresSink(URI.create("postgres://philipp:test_pwd@127.0.0.1:5432/sampledb"), 1000));

        // Finally execute the Stream:
        env.execute("Max Temperature By Day example");
    }
}