import React, { useEffect, useRef } from 'react';
import L from 'leaflet';
import { GeoSample, SavedBuoy } from '../types/openkayak';
import { OsmdroidTileStore } from '../services/osmdroidTileStore';

// Custom Osmdroid Leaflet TileLayer that caches all viewed tiles into Wear OS storage
const OsmdroidTileLayer = L.TileLayer.extend({
  createTile: function (coords: L.Coords, done: L.DoneCallback) {
    const tile = document.createElement('img');
    tile.alt = '';
    tile.setAttribute('role', 'presentation');

    const url = (this as any).getTileUrl(coords);
    OsmdroidTileStore.fetchAndCacheTile(url)
      .then((src) => {
        tile.src = src;
        done(undefined, tile);
      })
      .catch(() => {
        tile.src = url;
        done(undefined, tile);
      });

    return tile;
  },
});

export interface WaypointMarker {
  id: string;
  order: number;
  latitude: number;
  longitude: number;
  label?: string;
}

interface MapViewProps {
  points?: GeoSample[];
  buoys?: SavedBuoy[];
  currentLocation?: GeoSample | null;
  ambient?: boolean;
  followPosition?: boolean;
  zoom?: number;
  routeColor?: string;
  circuitRouteLine?: Array<[number, number]>;
  circuitWaypoints?: WaypointMarker[];
  circuitColor?: string;
  onPickCoordinate?: (coords: { latitude: number; longitude: number }) => void;
  pickedCoordinate?: { latitude: number; longitude: number } | null;
  interactive?: boolean;
  className?: string;
}

export const MapView: React.FC<MapViewProps> = ({
  points = [],
  buoys = [],
  currentLocation,
  ambient = false,
  followPosition = true,
  zoom = 16,
  routeColor = '#76F4D0',
  circuitRouteLine,
  circuitWaypoints = [],
  circuitColor = '#A855F7',
  onPickCoordinate,
  pickedCoordinate,
  interactive = true,
  className = 'w-full h-full min-h-[140px]',
}) => {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<L.Map | null>(null);
  const trackLayerRef = useRef<L.Polyline | null>(null);
  const circuitLayerRef = useRef<L.Polyline | null>(null);
  const markersLayerRef = useRef<L.LayerGroup | null>(null);
  const circuitMarkersRef = useRef<L.LayerGroup | null>(null);
  const boatMarkerRef = useRef<L.Marker | null>(null);
  const pickedMarkerRef = useRef<L.Marker | null>(null);

  // Initialize Map
  useEffect(() => {
    if (!mapContainerRef.current) return;

    const initialLat =
      currentLocation?.latitude ||
      (circuitWaypoints.length > 0 ? circuitWaypoints[0].latitude : null) ||
      points[0]?.latitude ||
      43.5585;
    const initialLon =
      currentLocation?.longitude ||
      (circuitWaypoints.length > 0 ? circuitWaypoints[0].longitude : null) ||
      points[0]?.longitude ||
      -5.9102;

    const map = L.map(mapContainerRef.current, {
      center: [initialLat, initialLon],
      zoom,
      zoomControl: interactive && !ambient,
      attributionControl: !ambient,
      dragging: interactive && !ambient,
      touchZoom: interactive && !ambient,
      scrollWheelZoom: interactive && !ambient,
      doubleClickZoom: interactive && !ambient,
    });

    mapInstanceRef.current = map;

    // osmdroid Tile Layer caching into Wear OS storage
    const tileLayer = new (OsmdroidTileLayer as any)(
      'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
      {
        maxZoom: 19,
        subdomains: 'abcd',
        attribution: ambient
          ? ''
          : '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> osmdroid WearOS cache',
      }
    );
    tileLayer.addTo(map);

    const markersGroup = L.layerGroup().addTo(map);
    markersLayerRef.current = markersGroup;

    const circuitGroup = L.layerGroup().addTo(map);
    circuitMarkersRef.current = circuitGroup;

    if (onPickCoordinate) {
      map.on('click', (e: L.LeafletMouseEvent) => {
        onPickCoordinate({
          latitude: e.latlng.lat,
          longitude: e.latlng.lng,
        });
      });
    }

    return () => {
      map.remove();
      mapInstanceRef.current = null;
    };
  }, []);

  // Update center & follow
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;

    if (currentLocation && followPosition) {
      map.panTo([currentLocation.latitude, currentLocation.longitude], {
        animate: true,
        duration: 0.8,
      });
    }
  }, [currentLocation, followPosition]);

  // Update Track Polyline (Athlete's recorded GPS track)
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;

    if (trackLayerRef.current) {
      map.removeLayer(trackLayerRef.current);
      trackLayerRef.current = null;
    }

    if (points.length > 1) {
      const latlngs: L.LatLngExpression[] = points.map((p) => [
        p.latitude,
        p.longitude,
      ]);
      const polyline = L.polyline(latlngs, {
        color: ambient ? '#FFFFFF' : routeColor,
        weight: ambient ? 3 : 4,
        opacity: ambient ? 0.85 : 0.95,
        lineCap: 'round',
        lineJoin: 'round',
      }).addTo(map);

      trackLayerRef.current = polyline;

      // Fit bounds if no followPosition and we have points
      if (!followPosition && points.length > 2 && (!circuitRouteLine || circuitRouteLine.length === 0)) {
        map.fitBounds(polyline.getBounds(), { padding: [15, 15] });
      }
    }
  }, [points, routeColor, ambient, followPosition, circuitRouteLine]);

  // Update Circuit Purple Line (Shortest path connecting buoy to buoy)
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;

    if (circuitLayerRef.current) {
      map.removeLayer(circuitLayerRef.current);
      circuitLayerRef.current = null;
    }

    if (circuitRouteLine && circuitRouteLine.length > 1) {
      const latlngs: L.LatLngExpression[] = circuitRouteLine.map((c) => [c[0], c[1]]);
      const polyline = L.polyline(latlngs, {
        color: ambient ? '#C084FC' : (circuitColor || '#A855F7'),
        weight: ambient ? 3.5 : 4.5,
        opacity: ambient ? 0.9 : 1.0,
        dashArray: ambient ? '6, 6' : undefined,
        lineCap: 'round',
        lineJoin: 'round',
      }).addTo(map);

      circuitLayerRef.current = polyline;

      // Auto-fit circuit if points array is small
      if (!followPosition && points.length <= 2) {
        map.fitBounds(polyline.getBounds(), { padding: [25, 25] });
      }
    }
  }, [circuitRouteLine, circuitColor, ambient, followPosition, points.length]);

  // Update Circuit Waypoints / Numbered Buoys (1, 2, 3...)
  useEffect(() => {
    const group = circuitMarkersRef.current;
    if (!group) return;
    group.clearLayers();

    circuitWaypoints.forEach((wp) => {
      const isStart = wp.order === 1;
      const wpIcon = L.divIcon({
        className: 'circuit-waypoint-marker',
        html: `
          <div style="
            background: ${isStart ? '#A855F7' : '#140c20'};
            border: 2px solid #C084FC;
            color: ${isStart ? '#FFFFFF' : '#C084FC'};
            width: 24px;
            height: 24px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 11px;
            font-weight: 900;
            box-shadow: 0 0 10px rgba(168, 85, 247, 0.8);
          ">
            ${wp.order}
          </div>
        `,
        iconSize: [24, 24],
        iconAnchor: [12, 12],
      });

      const marker = L.marker([wp.latitude, wp.longitude], { icon: wpIcon });
      marker.bindPopup(`
        <div style="background:#140c20; color:#fff; padding:6px; font-family:sans-serif; font-size:11px; border-radius:6px; border: 1px solid #A855F7;">
          <b style="color:#C084FC;">${isStart ? '🏁 META / SALIDA (PUNTO 1)' : `BOYA / GIRO #${wp.order}`}</b><br/>
          ${wp.label || ''}<br/>
          ${wp.latitude.toFixed(5)}, ${wp.longitude.toFixed(5)}
        </div>
      `);
      group.addLayer(marker);
    });
  }, [circuitWaypoints]);

  // Update Boat Position Marker
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;

    if (boatMarkerRef.current) {
      map.removeLayer(boatMarkerRef.current);
      boatMarkerRef.current = null;
    }

    const pos = currentLocation || (points.length > 0 ? points[points.length - 1] : null);
    if (pos) {
      const boatIcon = L.divIcon({
        className: 'boat-marker',
        html: `
          <div style="position: relative; width: 22px; height: 22px;">
            <div style="position: absolute; inset: 0; background: ${ambient ? '#FFFFFF' : '#76F4D0'}; border-radius: 50%; opacity: 0.4; animation: ping 1.5s cubic-bezier(0, 0, 0.2, 1) infinite;"></div>
            <div style="position: absolute; inset: 3px; background: ${ambient ? '#FFFFFF' : '#76F4D0'}; border: 2px solid #05090D; border-radius: 50%; box-shadow: 0 0 8px rgba(118,244,208,0.8);"></div>
          </div>
        `,
        iconSize: [22, 22],
        iconAnchor: [11, 11],
      });

      const marker = L.marker([pos.latitude, pos.longitude], {
        icon: boatIcon,
        zIndexOffset: 1000,
      }).addTo(map);

      boatMarkerRef.current = marker;
    }
  }, [currentLocation, points, ambient]);

  // Update Buoys & Markers
  useEffect(() => {
    const group = markersLayerRef.current;
    if (!group) return;
    group.clearLayers();

    buoys.forEach((buoy) => {
      const isUturn = buoy.turnType === 'UTURN';
      const isBuoy = buoy.turnType === 'BUOY';
      const color = isUturn ? '#FFB54A' : isBuoy ? '#FF8FA3' : '#76F4D0';
      const label = isUturn ? 'U' : isBuoy ? 'B' : '•';

      const buoyIcon = L.divIcon({
        className: 'custom-buoy-marker',
        html: `
          <div style="
            background: #101820;
            border: 2px solid ${color};
            color: ${color};
            width: 20px;
            height: 20px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 10px;
            font-weight: 800;
            box-shadow: 0 2px 5px rgba(0,0,0,0.6);
          ">${label}</div>
        `,
        iconSize: [20, 20],
        iconAnchor: [10, 10],
      });

      const marker = L.marker([buoy.latitude, buoy.longitude], {
        icon: buoyIcon,
      });

      marker.bindPopup(`
        <div style="background:#101820; color:#fff; padding:6px; font-family:sans-serif; font-size:11px; border-radius:6px;">
          <b style="color:${color};">${isUturn ? 'GIRO EN U' : isBuoy ? 'BOYA' : 'PUNTO'}</b><br/>
          ${buoy.repetitions > 1 ? `Repeticiones: ${buoy.repetitions}<br/>` : ''}
          ${buoy.angleDegrees > 0 ? `Ángulo: ${buoy.angleDegrees.toFixed(1)}°<br/>` : ''}
          ${buoy.latitude.toFixed(5)}, ${buoy.longitude.toFixed(5)}
        </div>
      `);

      group.addLayer(marker);
    });
  }, [buoys]);

  // Update picked marker
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;

    if (pickedMarkerRef.current) {
      map.removeLayer(pickedMarkerRef.current);
      pickedMarkerRef.current = null;
    }

    if (pickedCoordinate) {
      const icon = L.divIcon({
        className: 'picked-marker',
        html: `
          <div style="
            width: 20px;
            height: 20px;
            border-radius: 50%;
            border: 3px solid #C084FC;
            background: #A855F7;
            box-shadow: 0 0 12px #A855F7;
            animation: pulse 1s infinite;
          "></div>
        `,
        iconSize: [20, 20],
        iconAnchor: [10, 10],
      });

      const marker = L.marker([pickedCoordinate.latitude, pickedCoordinate.longitude], {
        icon,
      }).addTo(map);

      pickedMarkerRef.current = marker;
    }
  }, [pickedCoordinate]);

  return (
    <div
      ref={mapContainerRef}
      className={`relative rounded-xl overflow-hidden z-0 ${className}`}
      style={{
        filter: ambient ? 'grayscale(0.6) contrast(1.2)' : 'none',
      }}
    />
  );
};
