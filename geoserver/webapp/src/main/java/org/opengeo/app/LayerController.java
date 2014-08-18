package org.opengeo.app;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.wicket.util.resource.ResourceStreamNotFoundException;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.Styles;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.util.CloseableIterator;
import org.geoserver.config.GeoServer;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.ysld.YsldHandler;
import org.geotools.feature.NameImpl;
import org.geotools.geometry.jts.Geometries;
import org.geotools.styling.Style;
import org.geotools.styling.StyledLayerDescriptor;
import org.geotools.util.Version;
import org.geotools.util.logging.Logging;
import org.geotools.ysld.Ysld;
import org.json.simple.JSONObject;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.w3.xlink.ResourceType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.geoserver.catalog.Predicates.*;

@Controller
@RequestMapping("/backend/layers")
public class LayerController extends AppController {

    static Logger LOG = Logging.getLogger(LayerController.class);

    @Autowired
    public LayerController(GeoServer geoServer) {
        super(geoServer);
    }

    @RequestMapping(value="/{wsName}", method = RequestMethod.GET)
    public @ResponseBody JSONArr list(@PathVariable String wsName) {
        JSONArr arr = new JSONArr();

        Catalog cat = geoServer.getCatalog();

        if ("default".equals(wsName)) {
            WorkspaceInfo def = cat.getDefaultWorkspace();
            if (def != null) {
                wsName = def.getName();
            }
        }

        CloseableIterator<LayerInfo> it =
            cat.list(LayerInfo.class, equal("resource.namespace.prefix", wsName));
        try {
            while (it.hasNext()) {
                layer(arr.addObject(), it.next(), wsName, false);
            }
        }
        finally {
            it.close();
        }

        return arr;
    }

    @RequestMapping(value="/{wsName}/{name}", method = RequestMethod.GET)
    public @ResponseBody JSONObj get(@PathVariable String wsName, @PathVariable String name) {
        LayerInfo l = findLayer(wsName, name, geoServer.getCatalog());
        return layer(new JSONObj(), l, wsName, true);
    }

    @RequestMapping(value="/{wsName}/{name}/style", method = RequestMethod.GET, produces = YsldHandler.MIMETYPE)
    public @ResponseBody Object style(@PathVariable String wsName, @PathVariable String name)
        throws IOException {
        LayerInfo l = findLayer(wsName, name, geoServer.getCatalog());
        StyleInfo s = l.getDefaultStyle();
        if (s == null) {
            throw new NotFoundException(String.format("Layer %s:%s has no default style", wsName, name));
        }

        // if the style is already stored in Ysld format just pull it directly, otherwise encode the style
        if (YsldHandler.FORMAT.equalsIgnoreCase(s.getFormat())) {
            return dataDir().style(s);
        }
        else {
            Style style = s.getStyle();
            return Styles.sld(style);
        }
    }

    @RequestMapping(value="/{wsName}/{name}/style", method = RequestMethod.PUT, consumes = YsldHandler.MIMETYPE)
    public @ResponseBody void style(@RequestBody byte[] rawStyle, @PathVariable String wsName, @PathVariable String name) {
        // first thing is sanity check on the style content
        try {
            Ysld.parse(ByteSource.wrap(rawStyle).openStream());
        } catch (Exception e) {
            throw new BadRequestException("Invalid Ysld", e);
        }

        Catalog cat = geoServer.getCatalog();
        WorkspaceInfo ws = findWorkspace(wsName, cat);
        LayerInfo l = findLayer(wsName, name, cat);

        StyleInfo s = l.getDefaultStyle();

        if (s == null) {
            // create one
            s = cat.getFactory().createStyle();
            s.setName(findUniqueStyleName(wsName, name, cat));
            s.setFilename(s.getName()+".yaml");
            s.setWorkspace(ws);
        }
        else {
            // we are converting from normal SLD?
            if (!YsldHandler.FORMAT.equalsIgnoreCase(s.getFormat())) {
                // reuse base file name
                String base = FilenameUtils.getBaseName(s.getFilename());
                s.setFilename(base + ".yaml");
            }
         }

        s.setFormat(YsldHandler.FORMAT);
        s.setFormatVersion(new Version("1.0.0"));

        // write out the resource
        OutputStream output = dataDir().style(s).out();
        try {
            try {
                IOUtils.copy(ByteSource.wrap(rawStyle).openStream(), output);
                output.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        finally {
            IOUtils.closeQuietly(output);
        }

        if (s.getId() == null) {
            cat.add(s);
        }
        else {
            cat.save(s);
        }
    }

    String findUniqueStyleName(String wsName, String name, Catalog cat) {
        String tryName = name;
        int i = 0;
        while (i++ < 100) {
            if (cat.getStyleByName(wsName, tryName) == null) {
                return tryName;
            }
            tryName = name + String.valueOf(i);
        }
        throw new RuntimeException("Unable to find unqiue name for style");
    }

    WorkspaceInfo findWorkspace(String wsName, Catalog cat) {
        WorkspaceInfo ws = cat.getWorkspaceByName(wsName);
        if (ws == null) {
            throw new NotFoundException(String.format("No such workspace %s", wsName));
        }
        return ws;
    }

    LayerInfo findLayer(String wsName, String name, Catalog cat) {
        LayerInfo l = cat.getLayerByName(new NameImpl(wsName, name));
        if (l == null) {
            throw new NotFoundException(String.format("No such layer %s:%s", wsName, name));
        }
        return l;
    }

    String type(ResourceInfo r)  {
        if (r instanceof CoverageInfo) {
            return "raster";
        }
        else {
            return "vector";
        }
    }

    String geometry(FeatureTypeInfo ft) {
        try {
            FeatureType schema = ft.getFeatureType();
            GeometryDescriptor gd = schema.getGeometryDescriptor();
            if (gd == null) {
                return "Vector";
            }

            Geometries geomType = Geometries.getForBinding((Class<? extends Geometry>) gd.getType().getBinding());
            return geomType.getName();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error looking up schema", e);
            return "Unknown";
        }
    }

    JSONObj layer(JSONObj obj, LayerInfo l, String wsName, boolean details) {
        ResourceInfo r = l.getResource();
        obj.put("name", l.getName())
            .put("workspace", wsName)
            .put("title", l.getTitle() != null ? l.getTitle() : r.getTitle())
            .put("type", type(r));

        if (r instanceof FeatureTypeInfo) {
            FeatureTypeInfo ft = (FeatureTypeInfo) r;
            obj.put("geometry", geometry(ft));
        }

        proj(obj, r);

        JSONObj bbox = obj.putObject("bbox");
        bbox(bbox.putObject("native"), r.getNativeBoundingBox());
        bbox(bbox.putObject("lonlat"), r.getLatLonBoundingBox());

        return obj;
    }

    JSONObj proj(JSONObj obj, ResourceInfo r) {
        JSONObj proj = obj.putObject("proj");
        proj.put("srs", r.getSRS());

        CoordinateReferenceSystem crs = r.getCRS();

        // type
        proj.put("type",
            crs instanceof ProjectedCRS ? "projected" : crs instanceof GeographicCRS ? "geographic" : "other");

        // units
        String units = null;
        try {
            // try to determine from actual crs
            String unit = crs.getCoordinateSystem().getAxis(0).getUnit().toString();
            if ("ft".equals(unit) || "feets".equals(unit))
                units = "ft";
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to determine units from crs", e);
        }
        if (units == null) {
            // fallback: meters for projected, otherwise degrees
            units = crs instanceof ProjectedCRS ? "m" : "degrees";
        }
        proj.put("unit", units);

        return obj;
    }

    JSONObj bbox(JSONObj obj, Envelope bbox) {
        Coordinate center = bbox.centre();
        obj.put("west", bbox.getMinX())
           .put("south", bbox.getMinY())
           .put("east", bbox.getMaxX())
           .put("north", bbox.getMaxY())
           .putArray("center").add(center.x).add(center.y);
        return obj;
    }

}