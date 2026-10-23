import { GeoSample } from '../types/openkayak';

export class GpxExporter {
  public static buildGpx(name: string, points: GeoSample[]): string {
    const trkpts = points
      .map((p) => {
        const timeIso = p.timeMs
          ? new Date(p.timeMs).toISOString()
          : new Date().toISOString();
        return `      <trkpt lat="${p.latitude.toFixed(7)}" lon="${p.longitude.toFixed(7)}">
        <time>${timeIso}</time>
        ${p.accuracyM !== undefined ? `<ele>0.0</ele><hdop>${(p.accuracyM / 5).toFixed(1)}</hdop>` : ''}
      </trkpt>`;
      })
      .join('\n');

    return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="OpenKayak-WearOS" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>${name}</name>
    <time>${new Date().toISOString()}</time>
  </metadata>
  <trk>
    <name>${name}</name>
    <type>kayaking</type>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>`;
  }

  public static downloadGpx(filename: string, gpxContent: string): void {
    const blob = new Blob([gpxContent], { type: 'application/gpx+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename.endsWith('.gpx') ? filename : `${filename}.gpx`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  public static parseGpx(gpxText: string): GeoSample[] {
    const points: GeoSample[] = [];
    const parser = new DOMParser();
    const xmlDoc = parser.parseFromString(gpxText, 'text/xml');
    const trkpts = xmlDoc.getElementsByTagName('trkpt');

    for (let i = 0; i < trkpts.length; i++) {
      const node = trkpts[i];
      const lat = parseFloat(node.getAttribute('lat') || '');
      const lon = parseFloat(node.getAttribute('lon') || '');
      const timeNode = node.getElementsByTagName('time')[0];
      const timeMs = timeNode?.textContent
        ? Date.parse(timeNode.textContent)
        : Date.now();

      if (!isNaN(lat) && !isNaN(lon)) {
        points.push({
          latitude: lat,
          longitude: lon,
          timeMs,
        });
      }
    }
    return points;
  }
}
