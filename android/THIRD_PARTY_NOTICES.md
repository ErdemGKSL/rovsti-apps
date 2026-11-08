# Third-party notices (Rovsti Android UI)

Code under `android/` and the Rovsti skill `.claude/skills/convx-motion-compose/` contains portions ported from the
following projects. Files with ported code carry a header naming the source.

## glisse

- Source: https://github.com/0kzh/glisse ("iOS 26 Liquid Glass for the web", TypeScript)
- Used for: spring solver and constants, press/hold behaviour, refraction lens and rim-light maths, menu morph,
  tab bar / segmented control / switch / slider / input geometry and gesture rules, glass tokens.
- glisse credits, in its own comments, Aave's web liquid glass (lens shape) and kube.io's CSS/SVG liquid glass article
  (Snell's-law profile) as sources of its maths.

```
MIT License

Copyright (c) 2026 Kelvin Zhang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Not used as a code source

Convx (https://github.com/cosmictaserdev-creator/Convx) is GPL-3.0. It was studied for techniques and numeric
parameters only; no Convx code is included.
