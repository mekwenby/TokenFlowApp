-keepattributes *Annotation*
-dontwarn org.commonmark.**

# EvalEx retains this provided-only Lombok annotation in its published bytecode.
-dontwarn lombok.Generated

# XMLBeans stores generated schema class names in binary .xsb resources.
-keepnames class org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder
-keepnames class org.apache.xmlbeans.metadata.system.sXMLSCHEMA.TypeSystemHolder
-keepnames class org.openxmlformats.schemas.**
-keepnames class com.microsoft.schemas.**
-keepnames class org.etsi.uri.**
-keepnames class org.w3.x2000.x09.**
-keep,allowoptimization class org.openxmlformats.schemas.**.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}
-keep,allowoptimization class com.microsoft.schemas.**.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}
-keep,allowoptimization class org.etsi.uri.**.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}
-keep,allowoptimization class org.w3.x2000.x09.**.impl.** {
    public <init>(org.apache.xmlbeans.SchemaType);
}

# Optional annotations and OSGi integration referenced by Log4j.
-dontwarn aQute.bnd.annotation.baseline.BaselineIgnore
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.osgi.framework.wiring.BundleRevision

# Optional PDFBox JPEG 2000 decoder.
-dontwarn com.gemalto.jp2.JP2Decoder

# Desktop-only POI diagnostics and rendering helpers.
-dontwarn java.awt.Color
-dontwarn java.awt.Dimension
-dontwarn java.awt.Rectangle
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel

# Optional XMLBeans StAX and Saxon entry points not used by attachment extraction.
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn net.sf.saxon.Configuration
-dontwarn net.sf.saxon.dom.DOMNodeWrapper
-dontwarn net.sf.saxon.om.Item
-dontwarn net.sf.saxon.om.NamespaceUri
-dontwarn net.sf.saxon.om.NodeInfo
-dontwarn net.sf.saxon.om.Sequence
-dontwarn net.sf.saxon.om.SequenceTool
-dontwarn net.sf.saxon.sxpath.IndependentContext
-dontwarn net.sf.saxon.sxpath.XPathDynamicContext
-dontwarn net.sf.saxon.sxpath.XPathEvaluator
-dontwarn net.sf.saxon.sxpath.XPathExpression
-dontwarn net.sf.saxon.sxpath.XPathStaticContext
-dontwarn net.sf.saxon.sxpath.XPathVariable
-dontwarn net.sf.saxon.tree.wrapper.VirtualNode
-dontwarn net.sf.saxon.value.DateTimeValue
-dontwarn net.sf.saxon.value.GDateValue
