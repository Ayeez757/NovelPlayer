export interface YamlTreeNodeModel {
  label: string
  kind: 'object' | 'array' | 'value'
  meta?: string
  valueLabel?: string
  children?: YamlTreeNodeModel[]
  accent?: 'coral' | 'amber' | 'cyan' | 'teal' | 'violet' | 'rose'
}
