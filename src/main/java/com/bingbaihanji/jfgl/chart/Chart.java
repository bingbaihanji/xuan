package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.scene.GroupNode;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for all chart types.
 */
public abstract class Chart extends GroupNode {

    private final DrawEngine engine;

    private final Rect area;

    private final List<ChartSeries> series = new ArrayList<>();

    private String title;

    private String xAxisLabel;

    private String yAxisLabel;

    public Chart(DrawEngine engine, Rect area) {
        this.engine = engine;
        this.area = area;
    }

    public void addSeries(ChartSeries chartSeries) {
        series.add(chartSeries);
    }

    public abstract void rebuild();

    public DrawEngine getEngine() {
        return engine;
    }

    public Rect getArea() {
        return area;
    }

    public List<ChartSeries> getSeries() {
        return series;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getXAxisLabel() {
        return xAxisLabel;
    }

    public void setXAxisLabel(String xAxisLabel) {
        this.xAxisLabel = xAxisLabel;
    }

    public String getYAxisLabel() {
        return yAxisLabel;
    }

    public void setYAxisLabel(String yAxisLabel) {
        this.yAxisLabel = yAxisLabel;
    }

    /**
     * Represents a data series in a chart.
     */
    public static class ChartSeries {

        private String name;

        private List<double[]> data;

        private Color color;

        public ChartSeries(String name, List<double[]> data, Color color) {
            this.name = name;
            this.data = data;
            this.color = color;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<double[]> getData() {
            return data;
        }

        public void setData(List<double[]> data) {
            this.data = data;
        }

        public Color getColor() {
            return color;
        }

        public void setColor(Color color) {
            this.color = color;
        }
    }
}
