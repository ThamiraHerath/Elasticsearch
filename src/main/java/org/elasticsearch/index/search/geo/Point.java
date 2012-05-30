/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.search.geo;

/**
 */
public class Point implements Comparable<Point> {
    public double lat;
    public double lon;

    public Point() {
    }

    public Point(Point pt) {
        this.lat = pt.lat;
        this.lon = pt.lon;
    }

    public Point(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    @Override
    public int compareTo(Point pt) {
        int rtn;
        rtn = Double.compare(lat, pt.lat);
        if (rtn != 0) return rtn;
        rtn = Double.compare(lon, pt.lon);
        return rtn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Point point = (Point) o;

        if (Double.compare(point.lat, lat) != 0) return false;
        if (Double.compare(point.lon, lon) != 0) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        temp = lat != +0.0d ? Double.doubleToLongBits(lat) : 0L;
        result = (int) (temp ^ (temp >>> 32));
        temp = lon != +0.0d ? Double.doubleToLongBits(lon) : 0L;
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    public String toString() {
        return "[" + lat + ", " + lon + "]";
    }
}
