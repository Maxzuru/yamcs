#!/usr/bin/env python3
"""Validate common/application XML files against the XTCE 1.2 XSD (requires lxml)."""
from pathlib import Path
import sys
from lxml import etree

# Offline definitions for the XML namespace attributes referenced by the XSD.
# The actual XTCE schema is read unchanged from yamcs-xtce/SpaceSystemV1.2.xsd.
XML_ATTRIBUTES = '''<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
 targetNamespace="http://www.w3.org/XML/1998/namespace">
 <xs:attribute name="base" type="xs:anyURI"/>
 <xs:attribute name="lang" type="xs:language"/>
 <xs:attribute name="space"><xs:simpleType><xs:restriction base="xs:NCName">
 <xs:enumeration value="default"/><xs:enumeration value="preserve"/>
 </xs:restriction></xs:simpleType></xs:attribute>
</xs:schema>'''


class XmlNamespaceResolver(etree.Resolver):
    def resolve(self, url, public_id, context):
        if url == 'http://www.w3.org/2001/03/xml.xsd':
            return self.resolve_string(XML_ATTRIBUTES, context)
        return None


if __name__ == '__main__':
    repo = Path(__file__).resolve().parents[3]
    mdb = Path(sys.argv[1]) if len(sys.argv) > 1 else repo / 'examples/racs2-demo/src/main/yamcs/mdb/cfs.xml'
    parser = etree.XMLParser(no_network=True)
    parser.resolvers.add(XmlNamespaceResolver())
    schema = etree.XMLSchema(etree.parse(str(repo / 'yamcs-xtce/SpaceSystemV1.2.xsd'), parser))
    files = [mdb] if len(sys.argv) > 1 else sorted(mdb.parent.rglob('*.xml'))
    for file in files:
        schema.assertValid(etree.parse(str(file)))
        print('PASS: XTCE 1.2 schema validation: ' + str(file.relative_to(mdb.parent)))
