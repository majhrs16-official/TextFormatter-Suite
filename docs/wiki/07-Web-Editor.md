# Web Editor

## Overview

The Web Editor is a browser-based configuration tool for TextFormatter Suite. It provides a visual interface for creating and managing all suite configurations without editing YAML files directly.

## Access

### Local Development
```bash
cd suite/web-editor
npm install
npm run dev
# Open http://localhost:5173
```

### Production Deployment
```bash
cd suite/web-editor
npm run build
# Deploy dist/ to GitHub Pages or any static host
```

### GitHub Pages
The editor is automatically deployed to: `https://majhrs16-official.github.io/TextFormatter-Suite/`

## Interface Overview

### Layout

```
┌────────────────────────────────────────────────────────────────────┐
│ HEADER: [Logo] Suite | Navbar: Config ▸ iFlow ▸ Sync ▸ ... | 🌙 EN │
├──────┬───────────────────────────────┬────────────────────────────┤
│ RAIL │ TOOLBAR: Undo Redo Reset Copy │                            │
│      │ PALETTE: Channel Condition    │    WORKSPACE (Canvas)      │
│ Cfg  │ Transform Loop Sleep Output   │  ┌─────────────────────┐  │
│ Txf  │                                │  │  [Input]            │  │
│ iFlo │                                │  │    │Cond│──▶[Trans]  │  │
│ Sync │                                │  │    │    │    │       │  │
│ Perm │                                │  │    ▼    ▼    ▼       │  │
│ ...  │                                │  │  [Output]           │  │
│      │                                │  └─────────────────────┘  │
├──────┴───────────────────────────────┴────────────────────────────┤
│ STATUS: 12 cells · 9 edges · config 2.4 KB · [✓ ok · ✗ 2 err]    │
└──────────────────────────────────────────────────────────────────┘
```

### Navigation

| Area | Description |
|------|-------------|
| **Rail** | Main navigation: Config, TextFormatter, iFlow, Sync, Permissions, Modules |
| **Toolbar** | Actions: Undo, Redo, Reset, Duplicate, Validate, Download |
| **Palette** | Draggable items: Channels, Conditions, Transforms, Loops |
| **Canvas** | Main editing area - node graph for iFlow, cards for channels |
| **Properties** | Right panel - selected item properties |
| **Status Bar** | Validation status, file size, error count |

## Core Workflows

### Creating a Channel

1. Click **TextFormatter** in rail
2. Click **Add Channel** button or drag from palette
3. Fill in channel properties:
   - Name (becomes filename)
   - Type: CHAT / EVENT
   - Permissions
   - Messages, tooltips, sounds
   - Language settings
4. Click **Save** or auto-saves

### Building iFlow Rules

1. Select **iFlow** in rail
2. Drag nodes from palette to canvas:
   - **Input** - Message entry point
   - **Condition** - SpEL filter
   - **Transform** - Rewrite, sounds, sleep
   - **Loop** - Retry logic
   - **Sleep** - Delay
   - **Output** - Deliver to channel
   - **Redirect** - Forward to channel
3. Connect nodes by dragging from output to input ports
4. Configure each node in properties panel
5. Validate with **Validate** button

### Configuring Sync

1. Select **Sync** in rail
2. Click **Add Sink** or edit existing
3. Configure sink settings:
   - Discord: token, channel, intents
   - Telegram: token, chat ID
   - HTTP: webhook URL, port
   - WebSocket: port, token
4. Test with **Test** button

## Import/Export

### Export Project

1. Click **Export** in toolbar
2. Downloads `textformatter-suite.zip` containing:
   ```
   config.yml
   channels/
     chat.global.yml
     join.yml
     ...
   rules.yml
   translators/
     google.yml
     libre.yml
   sync/
     discord.yml
     telegram.yml
     ...
   manifest.json
   ```

### Import Project

1. Click **Import** in toolbar
2. Select `.zip` or drag & drop
3. Preview changes
5. Click **Import** to apply

### Round-trip Guarantee

- Import → Edit → Export = **byte-identical** YAML
- Unknown fields preserved in `extra` field
- No data loss on round-trip

## Validation

### Real-time Validation

- **Green check** = Valid
- **Yellow warning** = Non-blocking issue
- **Red error** = Blocking issue (prevents download)

### Validation Types

| Level | Icon | Description |
|-------|------|-------------|
| Error | 🔴 | Blocks download, must fix |
| Warning | 🟡 | Potential issue, review recommended |
| Info | 🔵 | Suggestion, optional |

### Common Validations

| Check | Error/Warning |
|-------|---------------|
| Duplicate channel names | Error |
| Missing required fields | Error |
| Invalid YAML syntax | Error |
| Unknown sync sink | Warning |
| Deprecated field | Warning |
| Missing translation provider | Warning |

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Z` | Undo |
| `Ctrl+Shift+Z` / `Ctrl+Y` | Redo |
| `Ctrl+S` | Save/Download |
| `Ctrl+Z` | Undo |
| `Ctrl+Shift+Z` | Redo |
| `Delete` | Delete selected |
| `Ctrl+D` | Duplicate |
| `Ctrl+A` | Select all |
| `Escape` | Deselect / Close modal |
| `Ctrl+Z` | Undo |
| `Ctrl+Shift+Z` | Redo |
| `Space` + Drag | Pan canvas |
| `Ctrl` + Scroll | Zoom |
| `Double-click` | Edit node/channel |

## Canvas Navigation

| Action | Method |
|--------|--------|
| Pan | `Space` + Drag / Middle mouse drag |
| Zoom | `Ctrl` + Scroll / Pinch |
| Fit to view | `F` key / Toolbar button |
| Center selection | `C` key |
| Minimap | Bottom-right corner |

## Node Graph (iFlow)

### Node Types

| Node | Icon | Description |
|------|------|-------------|
| Input | 📥 | Message entry point |
| Condition | ⬢ | SpEL filter |
| Transform | 🔄 | Rewrite, sounds, sleep |
| Loop | 🔄 | Retry/loop logic |
| Sleep | 😴 | Delay |
| Output | 📤 | Deliver to channel |
| Redirect | ➡️ | Forward to channel |

### Node Properties

| Property | Input | Condition | Transform | Loop | Sleep | Output | Redirect |
|----------|-------|-----------|-----------|------|-------|--------|----------|
| ID | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Label | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Channel | ✅ | ✅ | | | | ✅ | |
| Matcher | | ✅ | | | | | |
| Condition | | ✅ | | | | | |
| Transforms | | | ✅ | | | | |
| Target | | | | | | ✅ | ✅ |
| Priority | | ✅ | | | | | |
| Transforms | | | ✅ | | | | |

### Node Connections

- **Ports**: Input (top), Output (bottom)
- **Connection**: Drag from output port to input port
- **Mux**: Multiple inputs → one node
- **Fan-out**: One node → multiple outputs
- **Cycles**: Allowed (max-steps guard)

## Import/Export Details

### Export Formats

| Format | Extension | Use Case |
|--------|-----------|----------|
| ZIP | `.zip` | Full project backup |
| YAML | `.yml` | Individual files |
| JSON | `.json` | Programmatic use |

### Export Contents

```
textformatter-suite.zip
├── config.yml
├── channels/
│   ├── chat.global.yml
│   ├── join.yml
│   └── ...
├── rules.yml
├── translators/
│   ├── google.yml
│   └── libre.yml
├── sync/
│   ├── discord.yml
│   ├── telegram.yml
│   ├── http.yml
│   ├── tcp-udp.yml
│   ├── velocity.yml
│   └── websocket.yml
├── manifest.json
└── extensions/
    └── example-extension.yml
```

### Manifest.json

```json
{
  "schema": "v2.2",
  "suite-version": "2.1.0",
  "generated-at": "2024-01-15T10:30:00Z",
  "capabilities": { "transforms": true },
  "validation": { "errors": 0, "warnings": 0, "blocking": false, "issues": [] }
}
```

## Project Settings

### Access via Gear Icon

- **Project Name** - Display name
- **Description** - Project description
- **Version** - Suite version
- **Author** - Maintainer info
- **License** - License selector
- **Repository** - Git URL
- **Website** - Project URL

### Persistence

- **Auto-save** - Every 30 seconds
- **LocalStorage** - Auto-restore on reload
- **URL State** - Shareable URLs with state

## Collaboration

### Sharing Projects

1. Export project as `.zip`
2. Share via file transfer
3. Recipient imports `.zip`

### Real-time Collaboration (Future)

- Planned: WebRTC-based collaborative editing
- Presence indicators
- Conflict resolution

## Accessibility

### Keyboard Navigation

- Full keyboard navigation support
- Focus indicators
- ARIA labels
- Screen reader compatible

### Color Blind Friendly

- Color-blind safe palette
- Pattern differentiation
- High contrast mode

## Performance

### Large Projects

- Virtualized lists (1000+ channels)
- Canvas virtualization (1000+ nodes)
- Lazy loading
- Debounced validation

### Performance Tips

1. **Collapse groups** - Reduces DOM nodes
2. **Disable auto-validate** - For large projects
3. **Use filters** - Filter channels/nodes
4. **Close panels** - Reduce render load

## Browser Support

| Browser | Version | Support |
|---------|---------|---------|
| Chrome | 90+ | ✅ Full |
| Firefox | 88+ | ✅ Full |
| Safari | 15+ | ✅ Full |
| Edge | 90+ | ✅ Full |
| Mobile | - | ⚠️ Limited |

## PWA Support

- **Installable** - Add to home screen
- **Offline** - Service worker caching
- **Background sync** - Pending changes sync

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| Won't load | Clear cache, check console |
| Can't download | Check browser downloads permission |
| Validation stuck | Refresh, check console errors |
| Import fails | Check ZIP structure, file sizes |
| Canvas lag | Reduce nodes, disable auto-validate |

### Debug Console

```javascript
// Open DevTools Console
Suite.debug.dump()        // Dump full state
Suite.debug.validate()    // Run validation
Suite.debug.export()      // Export current state
```

## Keyboard Shortcuts Reference

### Global

| Shortcut | Action |
|----------|--------|
| `Ctrl+S` | Save/Download |
| `Ctrl+Z` | Undo |
| `Ctrl+Shift+Z` | Redo |
| `Ctrl+O` | Import |
| `Ctrl+E` | Export |
| `Ctrl+Shift+V` | Paste as new |
| `F` | Fit to view |
| `C` | Center selection |
| `Escape` | Deselect/Close modal |

### Canvas

| Shortcut | Action |
|----------|--------|
| `Space` + Drag | Pan |
| `Ctrl` + Scroll | Zoom |
| `Double-click` | Edit properties |
| `Delete` | Delete selected |
| `Ctrl+D` | Duplicate |
| `Ctrl+A` | Select all |
| `Escape` | Deselect |

### Editing

| Shortcut | Action |
|----------|--------|
| `Enter` | Confirm edit |
| `Escape` | Cancel edit |
| `Tab` | Next field |
| `Shift+Tab` | Previous field |
| `Ctrl+Enter` | Save |

## Themes

| Theme | Description |
|-------|-------------|
| Dark (Default) | Dark gray background |
| Light | Light gray background |
| High Contrast | WCAG AAA compliant |
| Sepia | Warm tones |
| Custom | User-defined CSS |

### Theme Switching

1. Click theme icon in header
2. Select theme
3. Persists in localStorage

## Extending the Editor

### Custom Node Types

```javascript
// Register custom node type
Suite.editor.registerNodeType({
  kind: 'custom-action',
  label: 'Custom Action',
  color: '#FF6B6B',
  ports: { inputs: 1, outputs: 1 },
  properties: [
    { key: 'action', type: 'select', options: ['a', 'b', 'c'] }
  ],
  render: (node, ctx) => { /* custom render */ }
});
```

### Custom Validators

```javascript
Suite.editor.validators.add('custom-rule', (state) => {
  const issues = [];
  // Custom validation logic
  return issues;
});
```

### Custom Exporters

```javascript
Suite.editor.exporters.register('custom-format', {
  export: (state) => {
    // Custom export logic
    return customFormat;
  }
});
```

## Performance Tuning

### Large Projects (>500 channels)

1. Disable auto-validate: Settings → Auto-validate: Off
2. Collapse channel groups: Click group headers
3. Use filters: Filter by name/type
4. Close properties panel when not needed

### Memory Management

- Large projects: Increase browser memory
- Chrome: `--js-flags="--max-old-space-size=4096"`
- Close unused tabs
- Restart browser periodically

## Mobile Support

| Feature | Support |
|---------|---------|
| View | ✅ Read-only |
| Edit | ⚠️ Limited |
| Touch gestures | ✅ Pan/zoom |
| Keyboard | ⚠️ Limited |

## Privacy & Security

- **No server communication** - Fully client-side
- **No tracking** - No analytics, no cookies
- **Local storage only** - Data stays in browser
- **CSP compliant** - Strict CSP headers
- **HTTPS required** - For production deployment

## Deployment

### Static Hosting

```bash
# Build
npm run build

# Deploy to any static host
# Netlify, Vercel, GitHub Pages, Cloudflare Pages, etc.
```

### Docker

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

### Environment Variables

```bash
# Build-time
VITE_API_URL=https://api.example.com
VITE_DEFAULT_LANG=en
VITE_THEME=dark
```

## Updates & Maintenance

### Updating

```bash
git pull origin main
npm ci
npm run build
```

### Backup

```bash
# Export project before updates
# Backup localStorage data
localStorage.getItem('suite-project-state')
```

## Support

### Getting Help

- **GitHub Issues** - Bug reports, feature requests
- **GitHub Discussions** - Questions, ideas
- **Discord** - Community support
- **Wiki** - This documentation

### Reporting Bugs

1. Check existing issues
2. Create minimal reproduction
3. Include browser/OS version
3. Include steps to reproduce
4. Include console errors

### Feature Requests

1. Check existing requests
2. Describe use case
3. Explain expected behavior
4. Add mockups if UI-related

---

*Web Editor v2.2 - Part of TextFormatter Suite*