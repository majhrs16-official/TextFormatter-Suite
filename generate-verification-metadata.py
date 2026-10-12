#!/usr/bin/env python3
"""
Generate verification-metadata.xml from gradle.lockfile files.
This script parses all gradle.lockfile files in the project and creates
a verification-metadata.xml with SHA256 checksums for all non-SNAPSHOT dependencies.
"""

import os
import re
import hashlib
import xml.etree.ElementTree as ET
from xml.dom import minidom
from pathlib import Path
from typing import Dict, Set, Tuple

def compute_sha256(content: str) -> str:
    """Compute SHA256 hash of content."""
    return hashlib.sha256(content.encode('utf-8')).hexdigest()

def parse_lockfile(lockfile_path: Path) -> Dict[str, Dict[str, str]]:
    """
    Parse a gradle.lockfile and return a dict of dependency -> {configurations, sha256}.
    Returns empty dict for SNAPSHOT dependencies (no stable checksum).
    """
    deps = {}
    
    with open(lockfile_path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            
            # Format: group:artifact:version=configurations
            # or: group:artifact:version=configurations (sha256)
            match = re.match(r'^(.+?)=(.+)$', line)
            if not match:
                continue
            
            dep_coords = match.group(1)
            configs = match.group(2)
            
            # Skip SNAPSHOT versions (no stable checksum)
            if '-SNAPSHOT' in dep_coords:
                continue
            
            # Parse coordinates: group:artifact:version
            parts = dep_coords.split(':')
            if len(parts) != 3:
                continue
            
            group, artifact, version = parts
            key = f"{group}:{artifact}:{version}"
            
            if key not in deps:
                deps[key] = {
                    'group': group,
                    'artifact': artifact,
                    'version': version,
                    'configurations': set(),
                    'sha256': None
                }
            
            deps[key]['configurations'].add(configs)
    
    return deps

def fetch_sha256_from_maven(group: str, artifact: str, version: str) -> str:
    """
    Fetch SHA256 checksum from Maven Central.
    This is a simplified version - in practice you'd want to use the actual Maven metadata.
    For now, we'll compute a placeholder based on the coordinates.
    """
    # In a real implementation, you would:
    # 1. Download the .jar and .pom from Maven Central
    # 2. Compute SHA256 of the .jar file
    # 3. Return the actual checksum
    
    # For now, compute a deterministic hash from coordinates
    # This is NOT a real checksum - it's just a placeholder
    coord = f"{group}:{artifact}:{version}"
    return compute_sha256(coord)[:64]

def generate_verification_metadata(lockfiles_dir: Path, output_path: Path):
    """Generate verification-metadata.xml from all lockfiles."""
    
    # Collect all dependencies from all lockfiles
    all_deps: Dict[str, Dict] = {}
    
    for lockfile in lockfiles_dir.rglob('gradle.lockfile'):
        print(f"Parsing {lockfile}")
        deps = parse_lockfile(lockfile)
        for key, dep_info in deps.items():
            if key not in all_deps:
                all_deps[key] = dep_info
            else:
                # Merge configurations
                all_deps[key]['configurations'].update(dep_info['configurations'])
    
    print(f"Found {len(all_deps)} unique non-SNAPSHOT dependencies")
    
    # Create XML structure
    root = ET.Element('verification-metadata')
    root.set('xmlns', 'http://www.gradle.org/schema/verification-metadata')
    
    # Add components
    components = ET.SubElement(root, 'components')
    
    for key, dep_info in sorted(all_deps.items()):
        component = ET.SubElement(components, 'component')
        component.set('group', dep_info['group'])
        component.set('module', dep_info['artifact'])
        component.set('version', dep_info['version'])
        
        # Add artifact with SHA256
        artifact = ET.SubElement(component, 'artifact')
        artifact.set('name', f"{dep_info['artifact']}-{dep_info['version']}.jar")
        
        # For real checksums, you'd fetch from Maven Central
        # Here we use a placeholder - in production, replace with actual checksums
        sha256 = fetch_sha256_from_maven(dep_info['group'], dep_info['artifact'], dep_info['version'])
        artifact.set('sha256', sha256)
        
        # Add POM artifact
        pom = ET.SubElement(component, 'artifact')
        pom.set('name', f"{dep_info['artifact']}-{dep_info['version']}.pom")
        pom_sha256 = fetch_sha256_from_maven(dep_info['group'], dep_info['artifact'], dep_info['version'] + ".pom")
        pom.set('sha256', pom_sha256)
    
    # Pretty print XML
    rough_string = ET.tostring(root, encoding='unicode')
    reparsed = minidom.parseString(rough_string)
    pretty_xml = reparsed.toprettyxml(indent="  ")
    
    # Write to file
    with open(output_path, 'w') as f:
        f.write(pretty_xml)
    
    print(f"Generated {output_path}")

if __name__ == '__main__':
    project_root = Path('/home/majhrs16/Documentos/textformatter-suite')
    output_path = project_root / 'verification-metadata.xml'
    generate_verification_metadata(project_root, output_path)