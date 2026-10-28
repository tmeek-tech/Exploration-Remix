"""Plant and crystal textures of the Deep Gardens and the Basalt Galleries (vegetation rework, part B).

Same rules as tools/plants.py: 16 by 16, vanilla style, colours from Taran's pictures, drawn with the shared helpers
there (register a painter with plants.register(PAINTERS, name, painter, glow=[...]) to give it a glow overlay). No
green anywhere in the Deep Gardens. textures.py merges this file after plants.py, so a painter here may repaint a plant
of plants.py.
"""

PAINTERS = {}
